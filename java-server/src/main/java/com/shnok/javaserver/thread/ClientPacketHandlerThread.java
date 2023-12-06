package com.shnok.javaserver.thread;

import com.shnok.javaserver.Config;
import com.shnok.javaserver.dto.clientpackets.*;
import com.shnok.javaserver.dto.serverpackets.*;
import com.shnok.javaserver.enums.ClientPacketType;
import com.shnok.javaserver.model.Point3D;
import com.shnok.javaserver.model.entities.Entity;
import com.shnok.javaserver.model.entities.PlayerInstance;
import com.shnok.javaserver.model.knownlist.ObjectKnownList;
import com.shnok.javaserver.service.ServerService;
import com.shnok.javaserver.service.ThreadPoolManagerService;
import com.shnok.javaserver.service.WorldManagerService;
import lombok.extern.log4j.Log4j2;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@Log4j2
public class ClientPacketHandlerThread extends Thread {
    private final GameClientThread client;
    private final byte[] data;

    public ClientPacketHandlerThread(GameClientThread client, byte[] data) {
        this.client = client;
        this.data = data;
    }

    @Override
    public void run() {
        handle();
    }

    public void handle() {
        ClientPacketType type = ClientPacketType.fromByte(data[0]);
        if(Config.PRINT_CLIENT_PACKETS) {
            log.debug("Received packet: {}", type);
        }
        switch (type) {
            case Ping:
                onReceiveEcho();
                break;
            case AuthRequest:
                onReceiveAuth(data);
                break;
            case SendMessage:
                onReceiveMessage(data);
                break;
            case RequestMove:
                onRequestCharacterMove(data);
                break;
            case LoadWorld:
                onRequestLoadWorld();
                break;
            case RequestRotate:
                onRequestCharacterRotate(data);
                break;
            case RequestAnim:
                onRequestCharacterAnimation(data);
                break;
            case RequestAttack:
                onRequestAttack(data);
                break;
            case RequestMoveDirection:
                onRequestCharacterMoveDirection(data);
                break;
        }
    }

    private void onReceiveEcho() {
        client.sendPacket(new PingPacket());
        client.setLastEcho(System.currentTimeMillis());

        Timer timer = new Timer(Config.CONNECTION_TIMEOUT_SEC, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (System.currentTimeMillis() - client.getLastEcho() >= Config.CONNECTION_TIMEOUT_SEC) {
                    log.info("User connection timeout.");
                    client.removeSelf();
                    client.disconnect();
                }
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void onReceiveAuth(byte[] data) {
        AuthRequestPacket packet = new AuthRequestPacket(data);
        String username = packet.getUsername();

        AuthResponsePacket authResponsePacket;
        if (ServerService.getInstance().userExists(username)) {
            authResponsePacket = new AuthResponsePacket(AuthResponsePacket.AuthResponseType.ALREADY_CONNECTED);
        } else if (username.length() <= 0 || username.length() > 16) {
            authResponsePacket = new AuthResponsePacket(AuthResponsePacket.AuthResponseType.INVALID_USERNAME);
        } else {
            authResponsePacket = new AuthResponsePacket(AuthResponsePacket.AuthResponseType.ALLOW);
            client.authenticated = true;
            client.setUsername(username);
        }

        client.sendPacket(authResponsePacket);

        if (client.authenticated) {
            client.authenticate();
        }
    }

    private void onReceiveMessage(byte[] data) {
        RequestSendMessagePacket packet = new RequestSendMessagePacket(data);
        String message = packet.getMessage();

        MessagePacket messagePacket = new MessagePacket(client.getUsername(), message);
        ServerService.getInstance().broadcast(messagePacket);
    }

    private void onRequestCharacterMove(byte[] data) {
        RequestCharacterMovePacket packet = new RequestCharacterMovePacket(data);
        Point3D newPos = packet.getPosition();

        PlayerInstance currentPlayer = client.getCurrentPlayer();
        currentPlayer.setPosition(newPos);

        // Update known list
        ThreadPoolManagerService.getInstance().execute(
                new ObjectKnownList.KnownListAsynchronousUpdateTask(client.getCurrentPlayer()));

        // Notify known list
        ObjectPositionPacket objectPositionPacket = new ObjectPositionPacket(currentPlayer.getId(), newPos);
        client.getCurrentPlayer().broadcastPacket(objectPositionPacket);
    }

    private void onRequestLoadWorld() {
        client.sendPacket(new PlayerInfoPacket(client.getPlayer()));

        // Loads surrounding area
        ThreadPoolManagerService.getInstance().execute(
                new ObjectKnownList.KnownListAsynchronousUpdateTask(client.getCurrentPlayer()));
    }

    private void onRequestCharacterRotate(byte[] data) {
        RequestCharacterRotatePacket packet = new RequestCharacterRotatePacket(data);

        // Notify known list
        ObjectRotationPacket objectRotationPacket = new ObjectRotationPacket(
                client.getCurrentPlayer().getId(), packet.getAngle());
        client.getCurrentPlayer().broadcastPacket(objectRotationPacket);
    }

    private void onRequestCharacterAnimation(byte[] data) {
        RequestCharacterAnimationPacket packet = new RequestCharacterAnimationPacket(data);

        // Notify known list
        ObjectAnimationPacket objectAnimationPacket = new ObjectAnimationPacket(
                client.getCurrentPlayer().getId(), packet.getAnimId(), packet.getValue());
        client.getCurrentPlayer().broadcastPacket(objectAnimationPacket);
    }

    private void onRequestAttack(byte[] data) {
        RequestAttackPacket packet = new RequestAttackPacket(data);

        Entity entity = WorldManagerService.getInstance().getEntity(packet.getTargetId());
        entity.inflictDamage(1);

        ApplyDamagePacket applyDamagePacket = new ApplyDamagePacket(
                client.getCurrentPlayer().getId(), packet.getTargetId(), packet.getAttackType(), 1);
        ServerService.getInstance().broadcast(applyDamagePacket);
    }

    private void onRequestCharacterMoveDirection(byte[] data) {
        RequestCharacterMoveDirection packet = new RequestCharacterMoveDirection(data);

        // Notify known list
        ObjectDirectionPacket objectDirectionPacket = new ObjectDirectionPacket(
                client.getCurrentPlayer().getId(), packet.getSpeed(), packet.getDirection());
        client.getCurrentPlayer().broadcastPacket(objectDirectionPacket);
    }
}
