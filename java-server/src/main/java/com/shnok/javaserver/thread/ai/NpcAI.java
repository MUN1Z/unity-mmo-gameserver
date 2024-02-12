package com.shnok.javaserver.thread.ai;

import com.shnok.javaserver.Config;
import com.shnok.javaserver.enums.EntityMovingReason;
import com.shnok.javaserver.model.entity.Entity;
import com.shnok.javaserver.pathfinding.node.Node;
import com.shnok.javaserver.service.GameTimeControllerService;
import com.shnok.javaserver.service.ThreadPoolManagerService;
import com.shnok.javaserver.enums.Intention;
import com.shnok.javaserver.model.Point3D;
import com.shnok.javaserver.model.entity.NpcInstance;
import com.shnok.javaserver.pathfinding.Geodata;
import com.shnok.javaserver.util.VectorUtils;
import lombok.extern.log4j.Log4j2;

import java.util.Random;
import java.util.concurrent.Future;

@Log4j2
public class NpcAI extends EntityAI implements Runnable {
    private NpcInstance npc;
    private Future<?> aiTask;

    public NpcAI() {
        startAITask();
    }

    @Override
    public void run() {
        onEvtThink();
    }

    @Override
    protected void onEvtThink() {
        if (thinking || owner == null) {
            return;
        }

        if(npc == null) {
            npc = (NpcInstance) owner;
        }

        thinking = true;

        System.out.println(getIntention());

        /* Is NPC waiting ? */
        if (getIntention() == Intention.INTENTION_IDLE) {
            thinkIdle();
        }

        if(getIntention() == Intention.INTENTION_ATTACK) {
            thinkAttack();
        }

        thinking = false;
        startAITask();
    }

    void thinkIdle() {
        /* Check if npc needs to change its intention */
        if (npc.isRandomWalk() && shouldWalk()) {
            movingReason = EntityMovingReason.Walking;

            // Update npc move speed to its walking speed
            npc.getStatus().setMoveSpeed(npc.getTemplate().getBaseWalkSpd());
            randomWalk();
        }
    }

    void thinkAttack() {
        onIntentionAttack();
    }

    private boolean shouldWalk() {
        Random r = new Random();
        if(r.nextInt(101) <=  Math.min((int) Config.AI_PATROL_CHANCE, 100)) {
            return true;
        }

        return false;
    }

    // default monster behaviour
    private void randomWalk() {
        if ((npc.getSpawnInfo() != null) && npc.isOnGeoData()) {
            try {
                Node n = Geodata.getInstance().findRandomNodeInRange(npc.getSpawnInfo().getSpawnPosition(), 6);
                setIntention(Intention.INTENTION_MOVE_TO, n.getCenter());
            } catch (Exception e) {
                if(Config.PRINT_PATHFINDER_LOGS) {
                    log.debug(e);
                }

                owner.setMoving(false);
                setIntention(Intention.INTENTION_IDLE);
            }
        }
    }

    @Override
    protected void onEvtDead() {
        super.onEvtDead();

        stopAITask();
    }

    private void startAITask() {
        if (aiTask == null) {
            aiTask = ThreadPoolManagerService.getInstance().scheduleAiAtFixedRate(this, 1000,
                    Config.AI_LOOP_RATE_MS);
        }
    }

    public void stopAITask() {
        if (aiTask != null) {
            if (getIntention() == Intention.INTENTION_MOVE_TO) {
                GameTimeControllerService.getInstance().removeMovingObject(owner);
            }

            aiTask.cancel(true);
            aiTask = null;
        }
    }

    @Override
    protected void onEvtArrived() {
        if (owner.moveToNextRoutePoint()) {
            return;
        }

        log.debug("Before " + getIntention());
        if (getIntention() == Intention.INTENTION_MOVE_TO) {
            setIntention(Intention.INTENTION_IDLE);
        }

        if(getIntention() == Intention.INTENTION_ATTACK) {
            setIntention(Intention.INTENTION_ATTACK);
        }
        log.debug(getIntention());
    }

    @Override
    protected void onIntentionMoveTo(Point3D destination) {
        super.onIntentionMoveTo(destination);

        // Check if still running
        if (owner.moveTo(destination)) {
            return;
        }

        setIntention(Intention.INTENTION_IDLE);
    }

    @Override
    protected void onIntentionIdle() {
        super.onIntentionIdle();

        if (getIntention() == Intention.INTENTION_MOVE_TO) {
            getOwner().setMoving(false);
        }

        intention = Intention.INTENTION_IDLE;
    }

    @Override
    protected void onEvtAttacked(Entity attacker) {
        super.onEvtAttacked(attacker);

        //TODO check range and set intention according
        // Stop moving if was patroling
        if(getIntention() == Intention.INTENTION_MOVE_TO && movingReason == EntityMovingReason.Walking) {
            GameTimeControllerService.getInstance().removeMovingObject(owner);
        }

        setIntention(Intention.INTENTION_ATTACK);
    }
}
