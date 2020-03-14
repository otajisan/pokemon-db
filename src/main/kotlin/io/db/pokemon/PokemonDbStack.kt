package pokemon

import software.amazon.awscdk.services.ecr.IRepository
import software.amazon.awscdk.services.ecr.Repository
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateServiceProps
import software.amazon.awscdk.core.*
import software.amazon.awscdk.services.ec2.*
import software.amazon.awscdk.services.ecs.*
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.LogGroupProps
import software.amazon.awscdk.services.rds.DatabaseInstance
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine
import software.amazon.awscdk.services.rds.DatabaseInstanceProps
import software.amazon.awscdk.services.servicediscovery.DnsRecordType
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespace
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespaceProps
import software.amazon.awscdk.services.servicediscovery.ServiceProps
import software.amazon.awscdk.services.ssm.StringParameter

class PokemonDbStack @JvmOverloads constructor(app: App, id: String, props: StackProps? = null) :
    Stack(app, id, props) {
  init {
    // VPC
    val vpc = Vpc(
        this, "vpc", VpcProps.builder()
        .maxAzs(2)
        .cidr("10.0.0.0/16")
        .build()
    )

    // RDS - PostgreSQL
    val databasePassword: String = StringParameter.valueFromLookup(this, "SONAR_JDBC_PASSWORD")
    val rds = DatabaseInstance(this, "pokemon-db-db", DatabaseInstanceProps.builder()
        .instanceIdentifier("pokemon-db-db")
        //.timezone("Asia/Tokyo")
        .masterUsername("pokemon-db")
        .masterUserPassword(SecretValue(databasePassword))
        .engine(DatabaseInstanceEngine.POSTGRES)
        .instanceClass(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
        .vpc(vpc)
        .autoMinorVersionUpgrade(true)
        .databaseName("pokemon-db")
        .multiAz(false)
        .backupRetention(Duration.days(7))
        .port(5432)
        .build()
    )

    val rdsSecurityGroup = SecurityGroup(this, "pokemon-db-db-sg", SecurityGroupProps.builder()
        .securityGroupName("pokemon-db-db-sg")
        .vpc(vpc)
        .build()
    )
    rds.connections.allowDefaultPortFrom(rdsSecurityGroup)

    // ECS Cluster
    val ecsCluster = Cluster(
        this, "pokemon-db-cluster", ClusterProps.builder()
        .clusterName("pokemon-db-cluster")
        .vpc(vpc)
        .build()
    )

    // ECR
    val ecrArn: String = this.node.tryGetContext("ecrArn").toString()
    val repository: IRepository = Repository.fromRepositoryArn(this, "pokemon-db", ecrArn)
    val containerImage = EcrImage.fromEcrRepository(repository, "latest")

    // TaskDefinition
    val taskDefinition =
        FargateTaskDefinition(
            this, "pokemon-db-fargate-task-definition",
            FargateTaskDefinitionProps.builder()
                .cpu(256)
                .memoryLimitMiB(1024)
                .build()
        )

    // ECS Log setting
    val awsLogDriver = AwsLogDriver(
        AwsLogDriverProps.builder()
            .logGroup(LogGroup(this, "pokemon-db-log", LogGroupProps.builder()
                .logGroupName("pokemon-db-service")
                .build()
            ))
            .streamPrefix("pokemon-db-service")
            .build()
    )

    // Container settings
    val redashCookieSecret = StringParameter.valueFromLookup(this, "POKEMON_REDASH_COOKIE_SECRET")
    val appContainer = taskDefinition.addContainer(
        "pokemon-db-container",
        ContainerDefinitionOptions.builder()
            .image(containerImage)
            .cpu(256)
            .memoryLimitMiB(2048)
            .environment(mutableMapOf(
                "POSTGRES_HOST" to rds.dbInstanceEndpointAddress,
                "POSTGRES_PORT" to rds.dbInstanceEndpointPort,
                "POSTGRES_USER" to "pokemon-db",
                "POSTGRES_PASSWORD" to databasePassword,
                "POSTGRES_DB" to "postgres",
                "REDASH_COOKIE_SECRET" to redashCookieSecret
            ))
            .logging(awsLogDriver)
            .build()
    )
    appContainer.addPortMappings(PortMapping.builder()
        .containerPort(80)
        .hostPort(80)
        .build()
    )

    // Fargate
    val fargateService = ApplicationLoadBalancedFargateService(
        this,
        "FargateService",
        ApplicationLoadBalancedFargateServiceProps.builder()
            .cluster(ecsCluster)
            .desiredCount(1)
            .taskDefinition(taskDefinition)
            // TODO: to be set false
            .publicLoadBalancer(true)
            .build()
    )

    // Cloud Map
    val namespace = PrivateDnsNamespace(
        this,
        "namespace",
        PrivateDnsNamespaceProps.builder()
            .name("pokemon.db.io")
            .vpc(vpc)
            .build()
    )

    val service = namespace.createService(
        "service",
        ServiceProps.builder()
            .namespace(namespace)
            .dnsRecordType(DnsRecordType.A_AAAA)
            .dnsTtl(Duration.seconds(30))
            .loadBalancer(true)
            .build()
    )

    service.registerLoadBalancer("load-balancer", fargateService.loadBalancer)
  }
}