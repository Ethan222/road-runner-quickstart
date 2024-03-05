package org.firstinspires.ftc.teamcode;

import com.acmerobotics.roadrunner.Action;
import com.acmerobotics.roadrunner.InstantAction;
import com.acmerobotics.roadrunner.ParallelAction;
import com.acmerobotics.roadrunner.Pose2d;
import com.acmerobotics.roadrunner.SequentialAction;
import com.acmerobotics.roadrunner.SleepAction;
import com.acmerobotics.roadrunner.Vector2d;
import com.acmerobotics.roadrunner.ftc.Actions;
import com.arcrobotics.ftclib.gamepad.GamepadEx;
import com.arcrobotics.ftclib.gamepad.GamepadKeys;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.enums.Alliance;
import org.firstinspires.ftc.teamcode.enums.Location;
import org.firstinspires.ftc.teamcode.enums.Side;
import org.firstinspires.ftc.teamcode.subsystems.Robot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Autonomous(preselectTeleOp = "Tele")
public class Auto extends LinearOpMode {
    private enum ParkPosition {
        CORNER, CENTER
    }
    private static Alliance alliance = Alliance.BLUE;
    private static Side side = Side.NEAR;
    private static boolean wait = false, goThroughStageDoor = true, placeOnBackdrop = true, useAprilTags = false, pickFromStack = true;
    private static ParkPosition parkPosition = ParkPosition.CORNER;
    private static boolean debugMode = true;

    private Robot robot;
    private TensorFlowObjectDetector propDetector;
    private Location propLocation;
    private ElapsedTime timer;
    private ScheduledExecutorService executorService;
    private boolean initialized = false, propLocationOverride = false;
    private static final double MIN_INIT_TIME = 5, WAIT_TIME = 15;
    private double beginningWaitTime = 0;
    private Telemetry.Item status, propLocationTelemetry;
    private CustomButton rb, lb, rt, lt, back;
    private CustomButton[] buttons;

    private class Trajectories {
        public Pose2d startPose;
        public Vector2d backdropPose = new Vector2d(56, 35-1+2), stackPose = new Vector2d(-57, 6), parkPose;
        public final Vector2d aprilTagOffset = new Vector2d(-7, 0);
        public Vector2d trussFront, trussBack;
        public Trajectories() {
            startPose = (side == Side.NEAR) ? new Pose2d(12+5, 64, Math.PI/2) : new Pose2d(-36+5, 64, Math.PI/2);
            trussFront = new Vector2d(-30, goThroughStageDoor ? 5 : 63);
            trussBack = trussFront.plus(new Vector2d(60, 0));
            parkPose = (parkPosition == ParkPosition.CORNER) ? new Vector2d(66, 55) : new Vector2d(66, 8);
        }
        public Action generateSpikeMarkTraj() {
            if(side == Side.NEAR) {
                switch (propLocation) {
                    case LEFT:
                        return robot.drive.actionBuilder(robot.drive.pose)
//                                .setReversed(true)
                                .strafeToSplineHeading(new Vector2d(35, 30), Math.PI)
                                .build();
                    case CENTER:
                        return robot.drive.actionBuilder(robot.drive.pose)
//                                .setReversed(true)
                                .strafeToSplineHeading(new Vector2d(17+5, 17), Math.PI)
                                .build();
                    case RIGHT:
                        return robot.drive.actionBuilder(robot.drive.pose)
//                                .setReversed(true)
                                .strafeTo(new Vector2d(12, 40))
                                .turnTo(Math.PI)
//                                .setReversed(false)
                                .strafeTo(new Vector2d(7+3, 30))
                                .build();
                }
            } else {
                switch(propLocation) {
                    case LEFT:
                        return robot.drive.actionBuilder(robot.drive.pose)
                                .strafeTo(new Vector2d(-35, 40))
                                .turnTo(0)
                                .strafeTo(new Vector2d(-30, 30))
                                .build();
                    case CENTER:
                        return robot.drive.actionBuilder(robot.drive.pose)
                                .strafeTo(new Vector2d(-40, 25))
                                .build();
                    case RIGHT:
                        return robot.drive.actionBuilder(robot.drive.pose)
                                .strafeToSplineHeading(new Vector2d(-55, 33), Math.PI)
                                .build();
                }
            }
            return null;
        }

        public Action generateBackdropTraj() {
            return robot.drive.actionBuilder(robot.drive.pose)
//                    .setReversed(true)
                    .strafeToSplineHeading(backdropPose, Math.PI)
                    .build();
        }
        public Action generateToStack() {
            return robot.drive.actionBuilder(robot.drive.pose)
                    .strafeTo(trussBack)
//                    .strafeTo(trussFront)
                    .strafeTo(stackPose)
                    .build();
        }
        public Action generateParkTraj() {
            return robot.drive.actionBuilder(robot.drive.pose)
                    .lineToX(robot.drive.pose.position.x - 5)
//                    .setReversed(true)
                    .strafeTo(new Vector2d(robot.drive.pose.position.x, parkPose.y))
                    .afterTime(.2, robot.outtake.lower())
                    .lineToX(parkPose.x)
                    .build();
        }
    }
    private Trajectories trajectories;

    @Override
    public void runOpMode() {
        initialize();
        initLoop();
        double initTime = getRuntime();
        resetRuntime();
        telemetry.clearAll();
        if(!propLocationOverride && initTime < MIN_INIT_TIME) {
            beginningWaitTime = MIN_INIT_TIME - initTime;
            continueDetectingTeamProp(beginningWaitTime);
        } else if(side == Side.NEAR && wait) {
            beginningWaitTime = WAIT_TIME;
            waitWithTelemetry();
        }
        try {
            propDetector.stopDetecting();
        } catch (Exception ignored) {}

        telemetry.clearAll();
        telemetry.setAutoClear(false);
        telemetry.addData("Running", "%s %s,  prop location = %s", alliance, side, propLocation);
        if(wait)
            telemetry.addLine("WAIT");
        if(side == Side.FAR || pickFromStack)
            telemetry.addLine("Go through " + (goThroughStageDoor ? "stage door" : "truss by wall"));
        telemetry.addLine((placeOnBackdrop ? "Place" : "Don't place") + " on backdrop");
        if(pickFromStack)
            telemetry.addLine("Pick from stack");
        if(placeOnBackdrop)
            telemetry.addLine((useAprilTags ? "Use" : "Don't use") + " april tags");
        if((!wait && !pickFromStack) || !placeOnBackdrop)
            telemetry.addData("Park in", parkPosition);
        if(debugMode)
            telemetry.addLine("DEBUG");
        status = telemetry.addData("\nStatus", "loading...");
        telemetry.addData("runtime", "%.1f", this::getRuntime);
        telemetry.update();

        trajectories = new Trajectories();
        robot.initDrive(hardwareMap, trajectories.startPose);
        telemetry.addData("pos", () -> poseToString(robot.drive.pose));

        robot.autoClaw.setPosition(.6);
        robot.outtake.releaser.close();

        if(side == Side.NEAR) {
            if (!pickFromStack || propLocation == Location.LEFT) {
                placePurplePixel();
                placeYellowPixel();
            } else {
                placeYellowPixel();
                placePurplePixel();
                if (propLocation == Location.RIGHT)
                    Actions.runBlocking(robot.drive.actionBuilder(robot.drive.pose)
                            .lineToX(trajectories.startPose.position.x)
                    );
            }
            if(pickFromStack) {
                pickFromStack();
                return;
            }
        } else {
            placePurplePixel();
            if(pickFromStack)
                pickFromStack();
        }
        if(debugMode) pause();

        if(pickFromStack) {
            pickFromStack();
            return;
        }

        status.setValue("parking at %.1fs", getRuntime());
        telemetry.update();
        Actions.runBlocking(trajectories.generateParkTraj());

        status.setValue("parked in %.1f s", getRuntime());
        telemetry.update();
        timer.reset();
        while(timer.seconds() < 3 && opModeIsActive());

//        Tele.setStartPose(robot.drive.pose);
    }

    private void placePurplePixel() {
        Actions.runBlocking(new ParallelAction(
                trajectories.generateSpikeMarkTraj(),
                robot.outtake.lower()
        ));
        robot.autoClaw.setPosition(.5);
        robot.intake.out(.5);
        executorService.schedule(robot.intake::stop, 250, TimeUnit.MILLISECONDS);
    }

    private void placeYellowPixel() {
        Action moveArm;
        switch (propLocation) {
            case LEFT:
                moveArm = robot.outtake.goToLeft();
                break;
            case RIGHT:
                moveArm = robot.outtake.goToRight();
                break;
            default:
                moveArm = new SleepAction(.1);
        }
        Actions.runBlocking(new SequentialAction(
                new ParallelAction(
                        trajectories.generateBackdropTraj(),
                        new SequentialAction(
                                robot.outtake.raise(),
                                new SleepAction(.2),
                                moveArm
                        )
                ),
                new SleepAction(.4),
                new InstantAction(robot.outtake::release)
        ));
    }
    private void pickFromStack() {
        status.setValue("moving to stack");
        telemetry.update();
        Actions.runBlocking(new ParallelAction(
                trajectories.generateToStack(),
                robot.outtake.lower()
        ));
    }

    private void wait(double time) {
        timer.reset();
        while(timer.seconds() < time && opModeIsActive());
    }

    private void pause() {
        status.setValue("paused (press a)");
        telemetry.update();
        while(!gamepad2.a && opModeIsActive());
        status.setValue("resuming");
        telemetry.update();
    }

    private void initialize() {
        status = telemetry.addData("Status", "initializing...").setRetained(true);
        telemetry.update();

        robot = new Robot(hardwareMap, telemetry);
        try {
            propDetector = new TensorFlowObjectDetector(hardwareMap);
        } catch(Exception e) {
            telemetry.log().add("camera init failed: " + e);
        }
        propLocation = Location.LEFT;
        timer = new ElapsedTime();
        executorService = Executors.newSingleThreadScheduledExecutor();
        GamepadEx driver2 = new GamepadEx(gamepad2);
        rb = new CustomButton(driver2, GamepadKeys.Button.RIGHT_BUMPER);
        lb = new CustomButton(driver2, GamepadKeys.Button.LEFT_BUMPER);
        back = new CustomButton(driver2, GamepadKeys.Button.BACK);
        buttons = new CustomButton[]{ rb, lb, back };
        rt = new CustomButton();
        lt = new CustomButton();

        telemetry.addData("debug mode (start + x/y)", () -> debugMode);
        telemetry.addLine().addData("\nalliance", () -> alliance).addData("side", () -> side);
        telemetry.addData("wait (RB)", () -> wait);
        telemetry.addData("go through stage door (LS)", () -> (side == Side.FAR || pickFromStack) ? goThroughStageDoor : "n/a");
        telemetry.addData("place on backdrop (RT)", () -> placeOnBackdrop);
        telemetry.addData("pick from stack (LT)", () -> pickFromStack);
        telemetry.addData("use april tags (LB)", () -> placeOnBackdrop ? useAprilTags : "n/a");
        telemetry.addData("park (RS)", () -> placeOnBackdrop && (wait || pickFromStack) ? "n/a" : parkPosition);
        propLocationTelemetry = telemetry.addData("\nprop location", null).setRetained(true);
    }
    private void initLoop() {
        while(opModeInInit()) {
            if(!initialized) {
                if(getRuntime() < MIN_INIT_TIME)
                    status.setValue("initializing...%.1f", MIN_INIT_TIME - getRuntime());
                else {
                    initialized = true;
                    status.setValue("initialized");
                }
            }

            getGamepadInput();

            if (!propLocationOverride) {
                try {
                    propDetector.setAlliance(alliance);
                    detectTeamProp();
                } catch (Exception e) {
                    propLocationTelemetry.setValue("error: " + e);
                }
            }

            telemetry.update();
        }
    }
    private void getGamepadInput() {
        for(CustomButton button : buttons)
            button.update();
        rt.update(gamepad2.right_trigger > .1);
        lt.update(gamepad2.left_trigger > .1);

        if((gamepad1.start && gamepad1.back) || (gamepad2.start && gamepad2.back))
            requestOpModeStop();

        // ALLIANCE
        if(gamepad2.x && !gamepad2.start) alliance = Alliance.BLUE;
        else if(gamepad2.b && !gamepad2.start) alliance = Alliance.RED;

        // SIDE
        if(gamepad2.a && !gamepad2.start) {
            side = Side.NEAR;
            wait = false;
            parkPosition = ParkPosition.CORNER;
        } else if(gamepad2.y && !gamepad2.start) {
            side = Side.FAR;
            wait = true;
        }

        // WAIT
        if(rb.getState() == CustomButton.State.JUST_UP && !gamepad2.back) wait = !wait;

        // GO THROUGH STAGE DOOR
        if(!gamepad2.back) {
            if (gamepad2.left_stick_y < -.5) goThroughStageDoor = true;
            else if (gamepad2.left_stick_y > .5) goThroughStageDoor = false;
        }

        // PLACE ON BACKDROP
        if (lt.getState() == CustomButton.State.JUST_UP && !gamepad2.back) placeOnBackdrop = !placeOnBackdrop;

        // PICK FROM STACK
        if(rt.getState() == CustomButton.State.JUST_UP) {
            pickFromStack = !pickFromStack;
            if(pickFromStack) {
                wait = false;
                goThroughStageDoor = true;
            } else if(side == Side.FAR) wait = true;
        }

        // APRIL TAGS
        if(placeOnBackdrop && lb.getState() == CustomButton.State.JUST_UP) useAprilTags = !useAprilTags;

        // PARK
        if(!gamepad2.back) {
            if (gamepad2.right_stick_y < -.1)
                parkPosition = ParkPosition.CENTER;
            else if (gamepad2.right_stick_y > .1)
                parkPosition = ParkPosition.CORNER;
        }

        // DEBUG MODE
        if(gamepad2.start && gamepad2.x) debugMode = true;
        else if(gamepad2.start && gamepad2.y) debugMode = false;

        // PROP LOCATION OVERRIDE
        if (gamepad2.dpad_left || gamepad2.dpad_up || gamepad2.dpad_right) {
            propLocationOverride = true;
            if (gamepad2.dpad_left)
                propLocation = Location.LEFT;
            else if (gamepad2.dpad_up)
                propLocation = Location.CENTER;
            else
                propLocation = Location.RIGHT;
            propLocationTelemetry.setValue(propLocation + " (override)");
        } else if (back.getState() == CustomButton.State.JUST_UP && back.getTimeDown() < .5)
            propLocationOverride = false;

        // PRELOAD
        if(gamepad2.back) {
            if (gamepad2.a && !gamepad2.start) robot.outtake.releaser.open();
            robot.autoClaw.rotateBy((gamepad2.left_stick_y + gamepad2.right_stick_y) / 300);
        }
    }
    private void detectTeamProp() {
        propDetector.update();
        propLocation = propDetector.getLocation();
        propLocationTelemetry.setValue(propLocation);
        telemetry.addLine();
        propDetector.telemetryAll(telemetry);
    }
    private void continueDetectingTeamProp(double time) {
        status.setValue("detecting prop location...%.1f", () -> time - getRuntime());
        propLocationTelemetry = telemetry.addData("prop location", propLocation);
        while(getRuntime() < time && opModeIsActive()) {
            detectTeamProp();
            telemetry.update();
        }
    }
    private void waitWithTelemetry() {
        status.setValue("waiting...%.1f", () -> WAIT_TIME - getRuntime());
        while(getRuntime() < WAIT_TIME && opModeIsActive())
            telemetry.update();
    }
    public static Alliance getAlliance() { return alliance; }
    private static String poseToString(Pose2d startPose) {
        return String.format("(%.1f, %.1f) @ %.1f deg", startPose.position.x, startPose.position.y, Math.toDegrees(startPose.heading.toDouble()));
    }
}
