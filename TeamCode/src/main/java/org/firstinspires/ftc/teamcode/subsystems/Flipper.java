package org.firstinspires.ftc.teamcode.subsystems;

import androidx.annotation.NonNull;

import com.acmerobotics.dashboard.config.Config;
import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import com.acmerobotics.roadrunner.Action;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.ElapsedTime;

@Config
public class Flipper extends CustomServo {
    public enum State {
        DOWN, UP
    }
    public static double UP_POSITION = .87, NORMAL_SPEED = .01, SLOW_SPEED = .0003, SLOW_DOWN_POSITION = .67, INCREMENT = .001;
    public Flipper(HardwareMap hardwareMap, String id, double minPos, double maxPos) {
        super(hardwareMap, id, minPos, maxPos);
    }
    public Action unflip(double delay) {
        return new Action() {
            private boolean initialized;
            private ElapsedTime timer;
            @Override
            public boolean run(@NonNull TelemetryPacket telemetryPacket) {
                if(!initialized) {
                    timer = new ElapsedTime();
                    initialized = true;
                    return true;
                }
                if(timer.seconds() < delay)
                    return true;
                if(getPosition() > SLOW_DOWN_POSITION) {
                    rotateBy(-NORMAL_SPEED);
                    return true;
                } else if(getPosition() > getMinPos()) {
                    rotateBy(-SLOW_SPEED);
                    return true;
                } else return false;
            }
        };
    }
    public Action unflip() { return unflip(0); }
    @Override public double getIncrement() {
        return INCREMENT;
    }

    public Action flip() {
        return goToPos(UP_POSITION);
    }

    public State getState() {
        double psn = getPosition();
        final double ERROR = .03;
        if(Math.abs(psn - getMinPos()) < ERROR)
            return State.DOWN;
        else if(Math.abs(psn - UP_POSITION) < ERROR)
            return State.UP;
        else return null;
    }
}
