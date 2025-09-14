package autoscaling;

import java.util.Arrays;
import java.util.List;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.PutScalingPolicyRequest;
import software.amazon.awssdk.services.autoscaling.model.PutScalingPolicyResponse;
import software.amazon.awssdk.services.autoscaling.model.Tag;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

import static autoscaling.AutoScale.EOL_VALUE;
import static autoscaling.AutoScale.PROJECT_VALUE;
import static autoscaling.AutoScale.ROLE_VALUE;
import static autoscaling.AutoScale.TYPE_VALUE;
import static autoscaling.AutoScale.configuration;


/**
 * Amazon AutoScaling resource class.
 */
public final class Aas {
    /**
     * Max size of ASG.
     */
    private static final Integer MAX_SIZE_ASG =
            configuration.getInt("asg_max_size");
    
    /**
     * Min size of ASG.
     */
    private static final Integer MIN_SIZE_ASG =
            configuration.getInt("asg_min_size");

    /**
     * Health Check grace period.
     */
    private static final Integer HEALTH_CHECK_GRACE_PERIOD =
            configuration.getInt("health_check_grace_period");
    
    /**
     * Cool down period Scale In.
     */
    private static final Integer COOLDOWN_PERIOD_SCALEIN =
            configuration.getInt("cool_down_period_scale_in");

    /**
     * Cool down period Scale Out.
     */
    private static final Integer COOLDOWN_PERIOD_SCALEOUT =
            configuration.getInt("cool_down_period_scale_out");

    /**
     * Number of instances to scale out by.
     */
    private static final Integer SCALING_OUT_ADJUSTMENT =
            configuration.getInt("scale_out_adjustment");
    
    /**
     * Number of instances to scale in by.
     */
    private static final Integer SCALING_IN_ADJUSTMENT =
            configuration.getInt("scale_in_adjustment");

    /**
     * ASG Cool down period in seconds.
     */
    private static final Integer COOLDOWN_PERIOD_ASG =
            configuration.getInt("asg_default_cool_down_period");

    /**
     * AAS Tags List.
     */
    private static final List<Tag> AAS_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).build());

    /**
     * Unused constructor.
     */
    private Aas() {
    }

    /**
     * Create auto scaling group.
     * Create and attach Cloud Watch Policies.
     *
     * @param aas            AAS Client
     * @param cloudWatch     CloudWatch client
     * @param targetGroupArn target group arn
     */
    public static void createAutoScalingGroup(final AutoScalingClient aas,
                                              final CloudWatchClient cloudWatch,
                                              final String targetGroupArn,
                                              final java.util.List<String> subnetIds) {
        // Create an Auto Scaling Group
        CreateAutoScalingGroupRequest autoScalingGroupRequest = CreateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(AutoScale.AUTO_SCALING_GROUP_NAME)
                .minSize(MIN_SIZE_ASG)
                .maxSize(MAX_SIZE_ASG)
                .desiredCapacity(MIN_SIZE_ASG)
                .defaultCooldown(COOLDOWN_PERIOD_ASG)
                .targetGroupARNs(targetGroupArn)
                .launchTemplate(b -> b.launchTemplateName(AutoScale.LAUNCH_TEMPLATE_NAME))
                .healthCheckType("EC2")
                .healthCheckGracePeriod(HEALTH_CHECK_GRACE_PERIOD)
                .vpcZoneIdentifier(String.join(",", subnetIds))
                .tags(AAS_TAGS_LIST)
                .build();

        aas.createAutoScalingGroup(autoScalingGroupRequest); 

        waitForAsgVisible(aas, AutoScale.AUTO_SCALING_GROUP_NAME, 60, 1000);

        // Scaling policies
        PutScalingPolicyResponse scaleOut =
                aas.putScalingPolicy(PutScalingPolicyRequest.builder()
                .policyName("scale-out-1")
                .autoScalingGroupName(AutoScale.AUTO_SCALING_GROUP_NAME)
                .adjustmentType("ChangeInCapacity")
                .cooldown(COOLDOWN_PERIOD_SCALEOUT)
                .scalingAdjustment(SCALING_OUT_ADJUSTMENT)
                .build());

        PutScalingPolicyResponse scaleIn =
                aas.putScalingPolicy(PutScalingPolicyRequest.builder()
                .policyName("scale-in-1")
                .autoScalingGroupName(AutoScale.AUTO_SCALING_GROUP_NAME)
                .adjustmentType("ChangeInCapacity")
                .cooldown(COOLDOWN_PERIOD_SCALEIN)
                .scalingAdjustment(SCALING_IN_ADJUSTMENT)
                .build());

        // Alarms wired to policy
        Cloudwatch.createScaleOutAlarm(cloudWatch, scaleOut.policyARN());
        Cloudwatch.createScaleInAlarm(cloudWatch, scaleIn.policyARN());
    }

    /**
     * Terminate auto scaling group.
     *
     * @param aas AAS client
     */
    public static void terminateAutoScalingGroup(final AutoScalingClient aas) {
        try {
                aas.deleteAutoScalingGroup(DeleteAutoScalingGroupRequest.builder()
                        .autoScalingGroupName(AutoScale.AUTO_SCALING_GROUP_NAME)
                        .forceDelete(true).build());
        } catch (Exception ignore) { }
    }

    private static void waitForAsgVisible(AutoScalingClient aas, String asgName, int attempts, long sleepMs) {
        for (int i = 0; i < attempts; i++) {
                try {
                if (!aas.describeAutoScalingGroups(b -> b.autoScalingGroupNames(asgName))
                        .autoScalingGroups().isEmpty()) return;
                } catch (Exception e) { }

                try { Thread.sleep(sleepMs); } 
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        throw new RuntimeException("ASG " + asgName + " did not become visible in time.");
    }
}
