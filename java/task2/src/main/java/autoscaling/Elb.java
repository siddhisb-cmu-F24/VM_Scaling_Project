package autoscaling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateListenerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateLoadBalancerResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.CreateTargetGroupResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;

import static autoscaling.AutoScale.EOL_VALUE;
import static autoscaling.AutoScale.PROJECT_VALUE;
import static autoscaling.AutoScale.ROLE_VALUE;
import static autoscaling.AutoScale.TYPE_VALUE;


/**
 * ELB resources class.
 */
public final class Elb {
    /**
     * ELB Tags.
     */
    public static final List<Tag> ELB_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).build());

    /**
     * Unused default constructor.
     */
    private Elb() {
    }

    /**
     * Create a target group.
     *
     * @param elb elb client
     * @param ec2 ec2 client
     * @return target group instance
     */
    public static TargetGroup createTargetGroup(
            final ElasticLoadBalancingV2Client elb,
            final Ec2Client ec2) {
        String vpcId = autoscaling.Ec2.getDefaultVPC(ec2).vpcId();

        CreateTargetGroupResponse targetGroupResponse =
        elb.createTargetGroup(CreateTargetGroupRequest.builder()
            .name(AutoScale.AUTO_SCALING_TARGET_GROUP)
            .protocol(ProtocolEnum.HTTP)
            .port(80)
            .vpcId(vpcId)
            .healthCheckProtocol(ProtocolEnum.HTTP)
            .healthCheckPath("/")
            .healthCheckIntervalSeconds(10)
            .healthyThresholdCount(2)
            .unhealthyThresholdCount(2)
            .targetType(TargetTypeEnum.INSTANCE)
            .build());

        TargetGroup targetGroup = targetGroupResponse.targetGroups().get(0);

        elb.addTags(b -> b.resourceArns(targetGroup.targetGroupArn()).tags(ELB_TAGS_LIST));
        return targetGroup;
    }

    /**
     * create a load balancer.
     *
     * @param elb             ELB client
     * @param ec2             EC2 client
     * @param securityGroupId Security group ID
     * @param targetGroupArn  target group ARN
     * @return Load balancer instance
     */
    public static LoadBalancer createLoadBalancer(
            final ElasticLoadBalancingV2Client elb,
            final Ec2Client ec2,
            final String securityGroupId,
            final String targetGroupArn) {
        DescribeSubnetsResponse subnetsResponse =
            ec2.describeSubnets(DescribeSubnetsRequest.builder().build());

        List<String> subnetIds = new ArrayList<String>();
        for (int i = 0; i < subnetsResponse.subnets().size() && subnetIds.size() < 2; i++) {
            subnetIds.add(subnetsResponse.subnets().get(i).subnetId());
        }

        CreateLoadBalancerResponse createLoadBalancerResponse =
            elb.createLoadBalancer(CreateLoadBalancerRequest.builder()
                .name(AutoScale.LOAD_BALANCER_NAME)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .securityGroups(securityGroupId)
                .subnets(subnetIds)
                .build());

        LoadBalancer loadBalancer = createLoadBalancerResponse.loadBalancers().get(0);

        elb.addTags(b -> b.resourceArns(loadBalancer.loadBalancerArn()).tags(ELB_TAGS_LIST));

        Action forwardAction = Action.builder()
                .type(ActionTypeEnum.FORWARD)
                .targetGroupArn(targetGroupArn)
                .build();

        elb.createListener(CreateListenerRequest.builder()
            .loadBalancerArn(loadBalancer.loadBalancerArn())
            .protocol(ProtocolEnum.HTTP)
            .port(80)
            .defaultActions(forwardAction)
            .build());

        return loadBalancer;
    }

    /**
     * Delete a load balancer.
     *
     * @param elb             LoadBalancing client
     * @param loadBalancerArn load balancer ARN
     */
    public static void deleteLoadBalancer(final ElasticLoadBalancingV2Client elb,
                                          final String loadBalancerArn) {
        try { 
            elb.deleteLoadBalancer(b -> b.loadBalancerArn(loadBalancerArn)); 
        } catch (Exception ignore) { }
    }

    /**
     * Delete Target Group.
     *
     * @param elb            ELB Client
     * @param targetGroupArn target Group ARN
     */
    public static void deleteTargetGroup(final ElasticLoadBalancingV2Client elb,
                                         final String targetGroupArn) {
        try { 
            elb.deleteTargetGroup(b -> b.targetGroupArn(targetGroupArn));
        } catch (Exception ignore) { }
    }
}
