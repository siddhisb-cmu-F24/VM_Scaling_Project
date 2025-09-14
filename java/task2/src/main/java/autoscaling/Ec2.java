package autoscaling;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
import software.amazon.awssdk.services.ec2.model.DeleteLaunchTemplateRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.Ipv6Range;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateTagSpecificationRequest;
import software.amazon.awssdk.services.ec2.model.LaunchTemplatesMonitoringRequest;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesMonitoringEnabled;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter;

import java.util.Arrays;
import java.util.List;

import static autoscaling.AutoScale.PROJECT_VALUE;
import static autoscaling.AutoScale.TYPE_VALUE;
import static autoscaling.AutoScale.ROLE_VALUE;
import static autoscaling.AutoScale.EOL_VALUE;;


/**
 * Class to manage EC2 resources.
 */
public final class Ec2 {
    /**
     * EC2 Tags List
     */
    private static final List<Tag> EC2_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).build());

    /**
     * Unused default constructor.
     */
    private Ec2() {
    }

    /**
     * Launch an Ec2 Instance.
     *
     * @param ec2                EC2Client
     * @param tagSpecification   TagsSpecified to create instance
     * @param amiId              amiId
     * @param instanceType       Type of instance
     * @param securityGroupId    Security Group
     * @param detailedMonitoring With Detailed Monitoring Enabled
     * @return Instance object
     */
    public static Instance launchInstance(final Ec2Client ec2,
                                          final TagSpecification tagSpecification,
                                          final String amiId,
                                          final String instanceType,
                                          final String securityGroupId,
                                          final Boolean detailedMonitoring) throws InterruptedException {
        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .imageId(amiId)
                .instanceType(InstanceType.fromValue(instanceType))
                .minCount(1)
                .maxCount(1)
                .monitoring(RunInstancesMonitoringEnabled.builder().enabled(true).build())
                .securityGroupIds(securityGroupId)
                .tagSpecifications(tagSpecification)
                .build();

        RunInstancesResponse runResponse = ec2.runInstances(runInstancesRequest);
        if (runResponse.instances().isEmpty()) {
            throw new RuntimeException("Failed to launch instance.");
        }

        String instanceId = runResponse.instances().get(0).instanceId();

        Ec2Waiter waiter = ec2.waiter();
        waiter.waitUntilInstanceRunning(DescribeInstancesRequest.builder().instanceIds(instanceId).build());

        DescribeInstancesResponse desc =
                ec2.describeInstances(DescribeInstancesRequest.builder().instanceIds(instanceId).build());
        if (!desc.reservations().isEmpty() && !desc.reservations().get(0).instances().isEmpty()) {
            return desc.reservations().get(0).instances().get(0);
        }
            
        throw new RuntimeException("Instance " + instanceId + " not found.");
    }

    /**
     * Get or create a security group and allow all HTTP inbound traffic.
     *
     * @param ec2               EC2 Client
     * @param securityGroupName the name of the security group
     * @param vpcId             the ID of the VPC
     * @return ID of security group
     */
    public static String getOrCreateHttpSecurityGroup(final Ec2Client ec2,
                                                      final String securityGroupName,
                                                      final String vpcId) {
        // Check if the security group already exists
        DescribeSecurityGroupsResponse existing = ec2.describeSecurityGroups(
            DescribeSecurityGroupsRequest.builder()
                    .filters(
                            Filter.builder().name("group-name").values(securityGroupName).build(),
                            Filter.builder().name("vpc-id").values(vpcId).build()
                    )
                    .build()
        );

        if (!existing.securityGroups().isEmpty()) {
            return existing.securityGroups().get(0).groupId();
        }

        // Create the Security Group
        CreateSecurityGroupRequest createRequest = CreateSecurityGroupRequest.builder()
                .groupName(securityGroupName)
                .description("SG for " + securityGroupName)
                .vpcId(vpcId)
                .build();

        CreateSecurityGroupResponse createResponse = ec2.createSecurityGroup(createRequest);
        String securityGroupId = createResponse.groupId();

        // Authorize HTTP Inbound Rule (Port 80)
        IpRange ipRange = IpRange.builder()
                .cidrIp("0.0.0.0/0") // Allow from anywhere
                .build();

        Ipv6Range ipv6Range = Ipv6Range.builder()
                .cidrIpv6("::/0") // Allow from anywhere
                .build();

        IpPermission ipPermission = IpPermission.builder()
                .ipProtocol("tcp")
                .fromPort(80)
                .toPort(80)
                .ipRanges(ipRange)
                .ipv6Ranges(ipv6Range)
                .build();

        AuthorizeSecurityGroupIngressRequest authorizeRequest = AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(ipPermission)
                .build();

        ec2.authorizeSecurityGroupIngress(authorizeRequest);

        return securityGroupId;
    }

    /**
     * Get the default VPC.
     * <p>
     * With EC2-Classic, your instances run in a single, flat network that you share with other customers.
     * With Amazon VPC, your instances run in a virtual private cloud (VPC) that's logically isolated to your AWS account.
     * <p>
     * The EC2-Classic platform was introduced in the original release of Amazon EC2.
     * If you created your AWS account after 2013-12-04, it does not support EC2-Classic,
     * so you must launch your Amazon EC2 instances in a VPC.
     * <p>
     * By default, when you launch an instance, AWS launches it into your default VPC.
     * Alternatively, you can create a non-default VPC and specify it when you launch an instance.
     *
     * @param ec2 EC2 Client
     * @return the default VPC object
     */
    public static Vpc getDefaultVPC(final Ec2Client ec2) {
        // Build a filter to find the default VPC
        Filter defaultVpcFilter = Filter.builder()
                .name("isDefault")
                .values("true")
                .build();

        // Describe VPCs with that filter
        DescribeVpcsResponse response = ec2.describeVpcs(r -> r.filters(defaultVpcFilter));

        if (!response.vpcs().isEmpty()) {
            // Return the first (and only) default VPC
            return response.vpcs().get(0);
        } else {
            throw new RuntimeException("No default VPC found in this region!");
        }
    }

    /**
     * Create launch template.
     * 
     * @param ec2 Ec2 Client
     */
    static void createLaunchTemplate(final Ec2Client ec2) {
        try {
            LaunchTemplateTagSpecificationRequest tagSpec =
                LaunchTemplateTagSpecificationRequest.builder()
                    .resourceType(ResourceType.INSTANCE.toString())
                    .tags(EC2_TAGS_LIST)
                    .build();

            String securityGroupIds = getOrCreateHttpSecurityGroup(
                    ec2, AutoScale.ELBASG_SECURITY_GROUP, getDefaultVPC(ec2).vpcId());

            RequestLaunchTemplateData data = RequestLaunchTemplateData.builder()
                .imageId(AutoScale.WEB_SERVICE)
                .instanceType(InstanceType.fromValue(AutoScale.INSTANCE_TYPE))
                .monitoring(LaunchTemplatesMonitoringRequest.builder().enabled(true).build())
                .securityGroupIds(securityGroupIds)
                .tagSpecifications(tagSpec)
                .build();

            ec2.createLaunchTemplate(
                CreateLaunchTemplateRequest.builder()
                    .launchTemplateName(AutoScale.LAUNCH_TEMPLATE_NAME)
                    .launchTemplateData(data)
                    .build()
            );
        } catch (Exception ignore) { }
    }

    /**
     * Terminate an Instance.
     *
     * @param ec2        Ec2 client
     * @param instanceId Instance Id to terminate
     */
    public static void terminateInstance(final Ec2Client ec2, final String instanceId) {
        try {
            ec2.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(instanceId).build());
        } catch (Exception ignore) { }
    }

    /**
     * Delete a Security group.
     *
     * @param ec2              ec2 client
     * @param elbSecurityGroup security group name
     */
    public static void deleteSecurityGroup(final Ec2Client ec2,
                                           final String elbSecurityGroup) {
        try {
            ec2.deleteSecurityGroup(b -> b.groupId(elbSecurityGroup));
        } catch (Exception ignore) { }
    }

    /**
     * Delete launch template.
     *
     * @param ec2 Ec2 Client instance
     */
    public static void deleteLaunchTemplate(final Ec2Client ec2) {
        try {
            ec2.deleteLaunchTemplate(DeleteLaunchTemplateRequest.builder()
                .launchTemplateName(AutoScale.LAUNCH_TEMPLATE_NAME).build());
        } catch (Exception ignore) { }
    }
}
