package autoscaling;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import static autoscaling.AutoScale.configuration;


/**
 * CloudWatch resources.
 */
public final class Cloudwatch {
    /**
     * Sixty seconds.
     */
    private static final Integer ALARM_PERIOD =
            configuration.getInt("alarm_period");
    
    /**
     * CPU Lower Threshold.
     */
    private static final Double CPU_LOWER_THRESHOLD =
            configuration.getDouble("cpu_lower_threshold");
    
    /**
     * CPU Upper Threshold.
     */
    private static final Double CPU_UPPER_THRESHOLD =
            configuration.getDouble("cpu_upper_threshold");
    
    /**
     * Alarm Evaluation Period out.
     */
    public static final Integer ALARM_EVALUATION_PERIODS_SCALE_OUT =
            configuration.getInt("alarm_evaluation_periods_scale_out");
    
    /**
     * Alarm Evaluation Period in.
     */
    public static final Integer ALARM_EVALUATION_PERIODS_SCALE_IN =
            configuration.getInt("alarm_evaluation_periods_scale_in");

    /**
     * Unused constructor.
     */
    private Cloudwatch() {
    }

    /**
     * Create Scale out alarm.
     *
     * @param cloudWatch cloudWatch instance
     * @param policyArn  policy ARN
     */
    public static void createScaleOutAlarm(final CloudWatchClient cloudWatch,
                                           final String policyArn) {
        cloudWatch.putMetricAlarm(PutMetricAlarmRequest.builder()
                .alarmName("cpu-scale-out")
                .namespace("AWS/EC2")
                .metricName("CPUUtilization")
                .dimensions(Dimension.builder()
                .name("AutoScalingGroupName").value(AutoScale.AUTO_SCALING_GROUP_NAME).build())
                .statistic(Statistic.AVERAGE)
                .period(ALARM_PERIOD)
                .evaluationPeriods(ALARM_EVALUATION_PERIODS_SCALE_OUT)
                .comparisonOperator("GreaterThanThreshold")
                .threshold(CPU_UPPER_THRESHOLD)
                .treatMissingData("missing")
                .alarmActions(policyArn)
                .unit("Percent")
                .build());
    }

    /**
     * Create ScaleIn Alarm.
     *
     * @param cloudWatch cloud watch instance
     * @param policyArn  policy Arn
     */
    public static void createScaleInAlarm(final CloudWatchClient cloudWatch,
                                          final String policyArn) {
        cloudWatch.putMetricAlarm(PutMetricAlarmRequest.builder()
                .alarmName("cpu-scale-in")
                .namespace("AWS/EC2")
                .metricName("CPUUtilization")
                .dimensions(Dimension.builder()
                .name("AutoScalingGroupName").value(AutoScale.AUTO_SCALING_GROUP_NAME).build())
                .statistic("Average")
                .period(ALARM_PERIOD)
                .evaluationPeriods(ALARM_EVALUATION_PERIODS_SCALE_IN)
                .comparisonOperator("LessThanThreshold")
                .threshold(CPU_LOWER_THRESHOLD)
                .treatMissingData("notBreaching")
                .alarmActions(policyArn)
                .unit("Percent")
                .build());
    }

    /**
     * Delete the two above Alarms.
     *
     * @param cloudWatch cloud watch client
     */
    public static void deleteAlarms(final CloudWatchClient cloudWatch) {
        try { 
                cloudWatch.deleteAlarms(b -> b.alarmNames("cpu-scale-out", "cpu-scale-in")); 
        } catch (Exception ignore) { }
    }
}
