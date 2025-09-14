package horizontal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utilities.Configuration;
import utilities.HttpRequest;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupResponse;
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
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.ec2.waiters.Ec2Waiter;

/**
 * Class for Task1 Solution.
 */
public final class HorizontalScaling {
    /** Project Tag value. */
    public static final String PROJECT_VALUE = "vm-scaling";

    /** Configuration file. */
    private static final Configuration CONFIGURATION =
        new Configuration("horizontal-scaling-config.json");

    /** Load Generator AMI. */
    private static final String LOAD_GENERATOR =
        CONFIGURATION.getString("load_generator_ami");

    /** Web Service AMI. */
    private static final String WEB_SERVICE =
        CONFIGURATION.getString("web_service_ami");

    /** Instance Type Name. */
    private static final String INSTANCE_TYPE =
        CONFIGURATION.getString("instance_type");

    /** Web Service Security Group Name. */
    private static final String WEB_SERVICE_SECURITY_GROUP =
        "web-service-security-group";

    /** Load Generator Security Group Name. */
    private static final String LG_SECURITY_GROUP = "lg-security-group";

    /** HTTP Port. */
    private static final Integer HTTP_PORT = 80;

    /** Launch Delay in milliseconds. */
    private static final long LAUNCH_DELAY = 100000L;

    /** Target RPS to stop provisioning. */
    private static final float RPS_TARGET = 50.0f;

    /** Sleep of one second in milliseconds. */
    private static final int ONE_SECOND_MS = 1000;

    /** Delay before retrying API call. */
    public static final int RETRY_DELAY_MILLIS = 100;

    /** Logger. */
    private static final Logger LOG =
        LoggerFactory.getLogger(HorizontalScaling.class);

    /** Private constructor to prevent instantiation. */
    private HorizontalScaling() { }

    /**
     * Task1 main method.
     *
     * @param args No args required
     * @throws Exception when something unpredictably goes wrong
     */
    public static void main(final String[] args) throws Exception {
        // BIG PICTURE: Provision resources to achieve horizontal scalability
        //  - Create security groups for Load Generator and Web Service
        //  - Provision a Load Generator instance
        //  - Provision a Web Service instance
        //  - Register Web Service DNS with Load Generator
        //  - Add Web Service instances to Load Generator
        //  - Terminate resources

        AwsCredentialsProvider credentialsProvider =
            DefaultCredentialsProvider.builder().build();

        // Create an Amazon EC2 Client
        Ec2Client ec2 = Ec2Client.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(credentialsProvider)
            .build();

        // Get the default VPC
        Vpc vpc = getDefaultVPC(ec2);

        // Create Security Groups in the default VPC
        String lgSecurityGroupId = getOrCreateHttpSecurityGroup(
            ec2, LG_SECURITY_GROUP, vpc.vpcId());
        String wsSecurityGroupId = getOrCreateHttpSecurityGroup(
            ec2, WEB_SERVICE_SECURITY_GROUP, vpc.vpcId());

        String lgInstanceId = launchInstance(
            ec2, LOAD_GENERATOR, INSTANCE_TYPE, lgSecurityGroupId);
        String loadGeneratorDNS = waitForRunningAndGetDns(ec2, lgInstanceId);

        String wsInstanceId = launchInstance(
            ec2, WEB_SERVICE, INSTANCE_TYPE, wsSecurityGroupId);
        String webServiceDNS = waitForRunningAndGetDns(ec2, wsInstanceId);

        // Initialize test
        String response = initializeTest(loadGeneratorDNS, webServiceDNS);

        // Get TestID
        String testId = getTestId(response);

        // Save launch time
        Date lastLaunchTime = new Date();

        // Monitor LOG file
        Ini ini = getIniUpdate(loadGeneratorDNS, testId);
        while (ini == null || !ini.containsKey("Test finished")) {
            ini = getIniUpdate(loadGeneratorDNS, testId);

            float rps = getRPS(ini);
            long now = System.currentTimeMillis();
            long sinceLast = now - lastLaunchTime.getTime();

            if (rps < RPS_TARGET && sinceLast >= LAUNCH_DELAY) {
                // Launch new WS
                String newWsId = launchInstance(
                    ec2, WEB_SERVICE, INSTANCE_TYPE, wsSecurityGroupId);
                String newWsDns = waitForRunningAndGetDns(ec2, newWsId);

                // Add to the running test
                addWebServiceInstance(loadGeneratorDNS, newWsDns, testId);

                // Reset cooldown timer
                lastLaunchTime = new Date();
            }
            try {
                Thread.sleep(ONE_SECOND_MS);
            } catch (InterruptedException ignored) {
                // ignore
            }
        }
    }

    /**
     * Get the latest RPS.
     *
     * @param ini INI file object
     * @return RPS value
     */
    private static float getRPS(final Ini ini) {
        float rps = 0.0f;
        for (String key : ini.keySet()) {
            if (key.startsWith("Current rps")) {
                rps = Float.parseFloat(key.split("=")[1]);
            }
        }
        return rps;
    }

    /**
     * Get the latest test log.
     *
     * @param loadGeneratorDNS DNS name of load generator
     * @param testId           Test ID string
     * @return INI object
     * @throws IOException on network failure
     */
    private static Ini getIniUpdate(final String loadGeneratorDNS,
                                    final String testId)
            throws IOException {
        String url = String.format(
            "http://%s/log?name=test.%s.log", loadGeneratorDNS, testId);
        String response = HttpRequest.sendGet(url);
        File log = new File(testId + ".log");
        FileUtils.writeStringToFile(
            log, response, Charset.defaultCharset());
        return new Ini(log);
    }

    /**
     * Get ID of test.
     *
     * @param response Response containing load generator output
     * @return Test ID string
     */
    private static String getTestId(final String response) {
        Pattern pattern = Pattern.compile("test\\.([0-9]*)\\.log");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Initializes Load Generator Test.
     *
     * @param loadGeneratorDNS DNS name of load generator
     * @param webServiceDNS    DNS name of web service
     * @return response of initialization (contains test ID)
     */
    private static String initializeTest(final String loadGeneratorDNS,
                                         final String webServiceDNS) {
        String response = "";
        boolean launchWebServiceSuccess = false;
        while (!launchWebServiceSuccess) {
            try {
                String url = String.format(
                    "http://%s/test/horizontal?dns=%s",
                    loadGeneratorDNS, webServiceDNS);
                response = HttpRequest.sendGet(url);
                LOG.info(response);
                launchWebServiceSuccess = true;
            } catch (Exception e) {
                // Retry until the instances are up and running
            }
        }
        return response;
    }

    /**
     * Add a Web Service VM to Load Generator.
     *
     * @param loadGeneratorDNS DNS name of Load Generator
     * @param webServiceDNS    DNS name of Web Service
     * @param testId           the test ID
     * @return String response
     */
    private static String addWebServiceInstance(final String loadGeneratorDNS,
                                                final String webServiceDNS,
                                                final String testId) {
        String response = "";
        boolean launchWebServiceSuccess = false;
        while (!launchWebServiceSuccess) {
            try {
                String url = String.format(
                    "http://%s/test/horizontal/add?dns=%s",
                    loadGeneratorDNS, webServiceDNS);
                response = HttpRequest.sendGet(url);
                LOG.info(response);
                launchWebServiceSuccess = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                    Ini ini = getIniUpdate(loadGeneratorDNS, testId);
                    if (ini.containsKey("Test finished")) {
                        launchWebServiceSuccess = true;
                        LOG.info("New WS is not added because the test "
                            + "already completed");
                    }
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            }
        }
        return response;
    }

    /**
     * Get the default VPC.
     *
     * <p>With EC2-Classic, your instances run in a single, flat network that
     * you share with other customers. With Amazon VPC, your instances run in
     * a virtual private cloud (VPC) that's logically isolated to your AWS
     * account.</p>
     *
     * <p>The EC2-Classic platform was introduced in the original release of
     * Amazon EC2. If you created your AWS account after 2013-12-04, it does
     * not support EC2-Classic, so you must launch your Amazon EC2 instances
     * in a VPC.</p>
     *
     * <p>By default, when you launch an instance, AWS launches it into your
     * default VPC. Alternatively, you can create a non-default VPC and
     * specify it when you launch an instance.</p>
     *
     * @param ec2 EC2 client
     * @return the default VPC object
     */
    public static Vpc getDefaultVPC(final Ec2Client ec2) {
        // Build a filter to find the default VPC
        Filter defaultVpcFilter = Filter.builder()
            .name("isDefault")
            .values("true")
            .build();

        // Describe VPCs with that filter
        DescribeVpcsResponse response =
            ec2.describeVpcs(r -> r.filters(defaultVpcFilter));

        if (!response.vpcs().isEmpty()) {
            // Return the first (and only) default VPC
            return response.vpcs().get(0);
        } else {
            throw new RuntimeException(
                "No default VPC found in this region!");
        }
    }

    /**
     * Get or create a security group and allow all HTTP inbound traffic.
     *
     * @param ec2               EC2 client
     * @param securityGroupName the name of the security group
     * @param vpcId             the ID of the VPC
     * @return ID of security group
     */
    public static String getOrCreateHttpSecurityGroup(final Ec2Client ec2,
                                                      final String
                                                          securityGroupName,
                                                      final String vpcId) {

        // Check if the security group already exists
        DescribeSecurityGroupsResponse existing =
            ec2.describeSecurityGroups(
                DescribeSecurityGroupsRequest.builder()
                    .filters(
                        Filter.builder()
                            .name("group-name")
                            .values(securityGroupName)
                            .build(),
                        Filter.builder()
                            .name("vpc-id")
                            .values(vpcId)
                            .build()
                    )
                    .build()
            );

        if (!existing.securityGroups().isEmpty()) {
            return existing.securityGroups().get(0).groupId();
        }

        // Create the Security Group
        CreateSecurityGroupRequest createRequest =
            CreateSecurityGroupRequest.builder()
                .groupName(securityGroupName)
                .description("SG for " + securityGroupName)
                .vpcId(vpcId)
                .build();

        CreateSecurityGroupResponse createResponse =
            ec2.createSecurityGroup(createRequest);
        String securityGroupId = createResponse.groupId();

        // Authorize HTTP Inbound Rule (Port 80)
        IpRange ipRange = IpRange.builder()
            .cidrIp("0.0.0.0/0")
            .build();

        Ipv6Range ipv6Range = Ipv6Range.builder()
            .cidrIpv6("::/0")
            .build();

        IpPermission ipPermission = IpPermission.builder()
            .ipProtocol("tcp")
            .fromPort(HTTP_PORT)
            .toPort(HTTP_PORT)
            .ipRanges(ipRange)
            .ipv6Ranges(ipv6Range)
            .build();

        AuthorizeSecurityGroupIngressRequest authorizeRequest =
            AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(ipPermission)
                .build();

        ec2.authorizeSecurityGroupIngress(authorizeRequest);

        return securityGroupId;
    }

    /**
     * Get instance object by ID.
     *
     * @param ec2        EC2 client instance
     * @param instanceId instance ID
     * @return Instance object
     */
    public static Instance getInstance(final Ec2Client ec2,
                                       final String instanceId) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
            .instanceIds(instanceId)
            .build();

        DescribeInstancesResponse response = ec2.describeInstances(request);
        if (!response.reservations().isEmpty()
            && !response.reservations().get(0).instances().isEmpty()) {
            return response.reservations()
                .get(0).instances().get(0);
        }
        throw new RuntimeException("Instance not found: " + instanceId);
    }

    /**
     * Launch an EC2 instance with project tagging.
     *
     * @param ec2              EC2 client
     * @param amiId            AMI ID
     * @param instanceTypeName instance type name
     * @param securityGroupId  security group ID
     * @return instance ID
     */
    private static String launchInstance(final Ec2Client ec2,
                                         final String amiId,
                                         final String instanceTypeName,
                                         final String securityGroupId) {

        // Tag Specifications
        Tag projectTag = Tag.builder()
            .key("project")
            .value(PROJECT_VALUE)
            .build();

        TagSpecification instanceTags = TagSpecification.builder()
            .resourceType(ResourceType.INSTANCE)
            .tags(projectTag)
            .build();

        TagSpecification volumeTags = TagSpecification.builder()
            .resourceType(ResourceType.VOLUME)
            .tags(projectTag)
            .build();

        TagSpecification eniTags = TagSpecification.builder()
            .resourceType(ResourceType.NETWORK_INTERFACE)
            .tags(projectTag)
            .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
            .imageId(amiId)
            .instanceType(InstanceType.fromValue(instanceTypeName))
            .minCount(1)
            .maxCount(1)
            .securityGroupIds(securityGroupId)
            .tagSpecifications(instanceTags, volumeTags, eniTags)
            .build();

        RunInstancesResponse runResponse = ec2.runInstances(runRequest);
        if (!runResponse.instances().isEmpty()) {
            return runResponse.instances().get(0).instanceId();
        }
        throw new RuntimeException("Failed to launch instance");
    }

    /**
     * Wait for instance to be running and return its public DNS.
     *
     * @param ec2        EC2 client
     * @param instanceId instance ID
     * @return instance public DNS
     */
    private static String waitForRunningAndGetDns(final Ec2Client ec2,
                                                  final String instanceId) {
        Ec2Waiter waiter = ec2.waiter();

        WaiterResponse<DescribeInstancesResponse> wait =
            waiter.waitUntilInstanceRunning(
                DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build()
            );
        wait.matched().response().ifPresent(r -> { });

        Instance inst = getInstance(ec2, instanceId);
        return inst.publicDnsName();
    }
}
