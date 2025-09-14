package autoscaling;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.ini4j.Ini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import utilities.Configuration;


/**
 * Main AutoScaling Task class.
 */
public final class AutoScale {
    /**
     * Configuration file
     */
    final static Configuration configuration = new Configuration("auto-scaling-config.json");

    /**
     * Project Tag value.
     */
    public static final String PROJECT_VALUE = "vm-scaling";
    
    /**
     * Type tag value.
     */
    public static final String TYPE_VALUE = "Project";
    
    /**
     * Role Tag value.
     */
    public static final String ROLE_VALUE = "Test";
    
    /**
     * EOL Tag value.
     */
    public static final String EOL_VALUE = "20201230";

    /**
     * HTTP Port.
     */
    static final Integer HTTP_PORT = 80;

    /**
     * ELB/ASG Security group Name.
     */
    static final String ELBASG_SECURITY_GROUP =
            "elb-asg-security-group";
    
    /**
     * Load Generator Security group Name.
     */
    static final String LG_SECURITY_GROUP =
            "lg-security-group";

    /**
     * Load Generator AMI.
     */
    private static final String LOAD_GENERATOR_AMI_ID =
            configuration.getString("load_generator_ami");

    /**
     * Web Service AMI.
     */
    static final String WEB_SERVICE =
            configuration.getString("web_service_ami");

    /**
     * Instance Type Name.
     */
    static final String INSTANCE_TYPE =
            configuration.getString("instance_type");

    /**
     * Auto Scaling Target Group Name.
     */
    static final String AUTO_SCALING_TARGET_GROUP =
            configuration.getString("auto_scaling_target_group");

    /**
     * Load Balancer Name.
     */
    static final String LOAD_BALANCER_NAME =
            configuration.getString("load_balancer_name");

    /**
     * Launch Template Name.
     */
    static final String LAUNCH_TEMPLATE_NAME =
            configuration.getString("launch_template_name");

    /**
     * Auto Scaling group name.
     */
    static final String AUTO_SCALING_GROUP_NAME =
            configuration.getString("auto_scaling_group_name");

    /**
     * Whether the Load Generator should be deleted at the end of the run.
     */
    private static final Boolean DELETE_LOAD_GENERATOR = true;

    /**
     * Delay before retrying API call.
     */
    public static final Integer RETRY_DELAY_MILLIS = 100;

    /**
     * Load Generator Tags List.
     */
    private static final List<Tag> LG_TAGS_LIST = Arrays.asList(
            Tag.builder().key("Project").value(PROJECT_VALUE).build(),
            Tag.builder().key("Type").value(TYPE_VALUE).build(),
            Tag.builder().key("Role").value(ROLE_VALUE).build(),
            Tag.builder().key("EOL").value(EOL_VALUE).build(),
            Tag.builder().key("Name").value("Load Generator").build());
    
    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AutoScale.class);

    /**
     * Main method to run the auto-scaling Task2.
     *
     * @param args No args required
     */
    public static void main(final String[] args) throws InterruptedException {
        AwsCredentialsProvider credentialsProvider =
                DefaultCredentialsProvider.builder().build();

        // Create an Amazon Ec2 Client
        final Ec2Client ec2 = Ec2Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.US_EAST_1)
                .build();

        // Create an Amazon auto scaling client
        final AutoScalingClient aas = AutoScalingClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.US_EAST_1)
                .build();

        // Create an ELB client
        final ElasticLoadBalancingV2Client elb = ElasticLoadBalancingV2Client.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.US_EAST_1)
                .build();

        // Create a cloudwatch client
        final CloudWatchClient cloudWatch = CloudWatchClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(Region.US_EAST_1)
                .build();

        runAutoScalingTask(ec2, aas, elb, cloudWatch);

        ec2.close();
        aas.close();
        elb.close();
        cloudWatch.close();
    }

    /**
     * Run the autoscaling task.
     *
     * @param ec2        EC2
     * @param aas        AAS
     * @param elb        ELB
     * @param cloudWatch Cloud watch Interface
     */
    private static void runAutoScalingTask(
            Ec2Client ec2,
            AutoScalingClient aas,
            ElasticLoadBalancingV2Client elb,
            CloudWatchClient cloudWatch) throws InterruptedException {
        // BIG PICTURE TODO: Programmatically provision autoscaling resources
        //   - Create security groups for Load Generator and ASG, ELB
        //   - Provision a Load Generator
        //   - Generate a Launch Configuration
        //   - Create a Target Group
        //   - Provision a Load Balancer
        //   - Associate Target Group with Load Balancer
        //   - Create an Autoscaling Group
        //   - Initialize Warmup Test
        //   - Initialize Autoscaling Test
        //   - Terminate Resources
        ResourceConfig resourceConfig = initializeResources(ec2, aas, elb, cloudWatch);
        resourceConfig = initializeTestResources(ec2, resourceConfig);

        executeTest(resourceConfig);
        destroy(ec2, aas, elb, cloudWatch, resourceConfig);
    }

    /**
     * Intialize Auto-scaling Task Resources.
     *
     * @param ec2        EC2 client
     * @param elb        ELB Client
     * @param aas        AAS Client
     * @param cloudWatch Cloud Watch Client
     * @return Load Balancer instance
     */
    private static ResourceConfig initializeResources(final Ec2Client ec2,
                                                      final AutoScalingClient aas,
                                                      final ElasticLoadBalancingV2Client elb,
                                                      final CloudWatchClient cloudWatch) {
        String vpcId = Ec2.getDefaultVPC(ec2).vpcId();
        String securityGroupIds = Ec2.getOrCreateHttpSecurityGroup(ec2, ELBASG_SECURITY_GROUP, vpcId);

        // Create Target Group
        TargetGroup targetGroup = Elb.createTargetGroup(elb, ec2);
        String targetGroupArn = targetGroup.targetGroupArn();

        DescribeSubnetsResponse subnetsResponse = ec2.describeSubnets(DescribeSubnetsRequest.builder().build());
        java.util.List<String> subnetIds = new java.util.ArrayList<>();
        for (int i = 0; i < subnetsResponse.subnets().size() && subnetIds.size() < 2; i++) {
            subnetIds.add(subnetsResponse.subnets().get(i).subnetId());
        }

        // Create Application Load Balancer
        LoadBalancer loadBalancer = Elb.createLoadBalancer(elb, ec2, securityGroupIds, targetGroupArn);
        String loadBalancerDNS = loadBalancer.dnsName();
        String loadBalancerArn = loadBalancer.loadBalancerArn();

        // Create Launch Template for Auto Scaling Group instances
        Ec2.createLaunchTemplate(ec2);

        // Create Auto Scaling Group
        Aas.createAutoScalingGroup(aas, cloudWatch, targetGroupArn, subnetIds);

        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.setTargetGroupArn(targetGroupArn);
        resourceConfig.setLoadBalancerArn(loadBalancerArn);
        resourceConfig.setLoadBalancerDns(loadBalancerDNS);
        resourceConfig.setSubnets(subnetIds);
        return resourceConfig;
    }

    /**
     * Create a load Generator and initialize test.
     *
     * @param ec2    EC2 client
     * @param config Resource configuration
     * @return config Resource configuration
     */
    public static ResourceConfig initializeTestResources(final Ec2Client ec2,
                                                         final ResourceConfig config) throws InterruptedException {
        String vpcId = Ec2.getDefaultVPC(ec2).vpcId();
        String securityGroupIds = Ec2.getOrCreateHttpSecurityGroup(ec2, LG_SECURITY_GROUP, vpcId);

        TagSpecification tagSpec = TagSpecification.builder()
            .resourceType(ResourceType.INSTANCE)
            .tags(LG_TAGS_LIST)
            .build();

        Instance loadGenerator = Ec2.launchInstance(
            ec2,
            tagSpec,
            LOAD_GENERATOR_AMI_ID,
            INSTANCE_TYPE,
            securityGroupIds,
            false
        );
        
        config.setLoadGeneratorDns(loadGenerator.publicDnsName());
        config.setLoadGeneratorID(loadGenerator.instanceId());
        
        return config;
    }

    /**
     * Execute auto scaling test.
     *
     * @param resourceConfig Resource configuration
     */
    public static void executeTest(ResourceConfig resourceConfig) {
        // Submit ELB DNS to Load Generator for Warmup test
        executeWarmUp(resourceConfig);

        // Submit ELB DNS to Load Generator for Auto-scaling test
        Boolean testStarted = false;
        String response = "";
        while (!testStarted) {
            try {
                response = Api.initializeTest(resourceConfig.getLoadGeneratorDns(), resourceConfig.getLoadBalancerDns());
                testStarted = true;
            } catch (Exception e) {
                // Ignore errors
                LOG.error("*");
            }
        }

        // Test started
        waitForTestEnd(resourceConfig, response);
    }

    /**
     * Execute warm-up test using API.
     *
     * @param resourceConfig Resource Configuration
     */
    private static void executeWarmUp(ResourceConfig resourceConfig) {
        Boolean warmupStarted = false;
        String warmupResponse = "";
        while (!warmupStarted) {
            try {
                warmupResponse = Api.initializeWarmup(resourceConfig.getLoadGeneratorDns(), resourceConfig.getLoadBalancerDns());
                LOG.info("Initialize warmup called");
                warmupStarted = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }

        // Test started
        waitForTestEnd(resourceConfig, warmupResponse);
        LOG.info("Warmup finished!");
    }


    /**
     * Wait For Test Execution to be complete.
     *
     * @param resourceConfig Resource Configuration
     * @param response       Response from Test Initialization.
     */
    private static void waitForTestEnd(ResourceConfig resourceConfig, String response) {
        try {
            Ini ini;
            do {
                ini = Api.getIniUpdate(resourceConfig.getLoadGeneratorDns(), Api.getTestId(response));
            } while (!ini.containsKey("Test finished"));
            LOG.info("Ini {}", ini);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Destroy all resources created by the task.
     *
     * @param aas            AmazonAutoScaling
     * @param ec2            AmazonEC2
     * @param elb            AmazonElasticLoadBalancing
     * @param cloudWatch     AmazonCloudWatch
     * @param resourceConfig Resource Configuration
     */
    public static void destroy(final Ec2Client ec2,
                               final AutoScalingClient aas,
                               final ElasticLoadBalancingV2Client elb,
                               final CloudWatchClient cloudWatch,
                               final ResourceConfig resourceConfig) {
        // Delete CloudWatch alarms
        try {
            Cloudwatch.deleteAlarms(cloudWatch);
        } catch (Exception ignore) { }

        // Delete Auto Scaling Group
        try {
            Aas.terminateAutoScalingGroup(aas);
        } catch (Exception ignore) { }

        // Delete Application Load Balancer
        try {
            if (resourceConfig.getLoadBalancerArn() != null) {
                Elb.deleteLoadBalancer(elb, resourceConfig.getLoadBalancerArn());
            }
        } catch (Exception ignore) { }

        try { Thread.sleep(5000L); } catch (InterruptedException ignore) { }

        // Delete Target Group
        try {
            if (resourceConfig.getTargetGroupArn() != null) {
                Elb.deleteTargetGroup(elb, resourceConfig.getTargetGroupArn());
            }
        } catch (Exception ignore) { }

        // Delete Launch Template
        try {
            Ec2.deleteLaunchTemplate(ec2);
        } catch (Exception ignore) { }
    }
}
