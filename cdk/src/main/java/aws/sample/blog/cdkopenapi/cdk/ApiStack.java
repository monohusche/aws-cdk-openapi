package aws.sample.blog.cdkopenapi.cdk;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import software.constructs.Construct;

import software.amazon.awscdk.BundlingOptions;
import software.amazon.awscdk.BundlingOutput;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.DockerImage;
import software.amazon.awscdk.DockerVolume;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.ILocalBundling;
import software.amazon.awscdk.IResolvable;
import software.amazon.awscdk.PhysicalName;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.AssetCode;
import software.amazon.awscdk.services.lambda.CfnFunction;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Permission;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.assets.Asset;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.CacheControl;
import software.amazon.awscdk.services.s3.deployment.ISource;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.apigateway.SpecRestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.cloudfront.Behavior;
import software.amazon.awscdk.services.cloudfront.CloudFrontWebDistribution;
import software.amazon.awscdk.services.cloudfront.OriginAccessIdentity;
import software.amazon.awscdk.services.cloudfront.S3OriginConfig;
import software.amazon.awscdk.services.cloudfront.SourceConfiguration;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.apigateway.AssetApiDefinition;
import software.amazon.awscdk.services.apigateway.InlineApiDefinition;

public class ApiStack extends Stack {

	private CfnOutput restIdOutput;

	private CfnOutput bucketNameOutput;

	private CfnOutput cloudFrontURLOutput;

	private CfnOutput cloudFrontDistributionIdOutput;

	public CfnOutput getRestIdOutput() {
		return restIdOutput;
	}

	public CfnOutput getBucketNameOutput() {
		return bucketNameOutput;
	}

	public CfnOutput getCloudFrontURLOutput() {
		return cloudFrontURLOutput;
	}

	public CfnOutput getCloudFrontDistributionIdOutput() {
		return cloudFrontDistributionIdOutput;
	}

	public ApiStack(final Construct scope, final String id, String stage) {
		this(scope, id, stage, null);
	}

	public ApiStack(final Construct scope, final String id, String stage,
			/* , String webBucketName Bucket webBucket, */ final StackProps props) {
		super(scope, id, props);

		System.err.println("ApiStack: after super constructor");

		List<String> apiPackagingInstructions = Arrays.asList(
				"/bin/sh", "-c",
				"pwd && ls -l && " +
						"mvn --no-transfer-progress clean package && " +
						"cp target/function.zip /asset-output/");

		BundlingOptions.Builder apiBuilderOptions = BundlingOptions.builder().command(apiPackagingInstructions)
				.image(software.amazon.awscdk.services.lambda.Runtime.JAVA_11.getBundlingImage()).volumes(
						singletonList(
								// Mount local .m2 repo to avoid download all the dependencies again inside the
								// container
								DockerVolume.builder()
										.hostPath(System.getProperty("user.home") + "/.m2/")
										.containerPath("/root/.m2/")
										.build()))
				.user("root")
				.local(new ILocalBundling() {

					@Override
					public @NotNull Boolean tryBundle(@NotNull String outputDir, @NotNull BundlingOptions options) {
						// Use container for all builds.
						return false;
					}

				})
				.outputType(BundlingOutput.ARCHIVED);

		AssetCode apiCode = Code.fromAsset("../app/",
				AssetOptions.builder()
						.bundling(apiBuilderOptions.command(apiPackagingInstructions).build())
						.build());
		System.err.println("ApiStack: created AssetCode "+ apiCode);

		Function apiLambda = new Function(this, "OpenAPIBlogLambda",
				FunctionProps.builder()
						.runtime(software.amazon.awscdk.services.lambda.Runtime.JAVA_11)
						.code(apiCode)
						.handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
						.memorySize(512)
						.timeout(Duration.seconds(30))
						.logRetention(RetentionDays.ONE_DAY)
						.build());

		System.err.println("ApiStack: created Function "+ apiLambda);

		// Get the construct for the API Lambda and override the Logical ID generated by
		// the CDK.
		CfnFunction apiCfnFunction = (CfnFunction) apiLambda.getNode().getDefaultChild();
		apiCfnFunction.overrideLogicalId("APILambda");

		Permission lamdaAPIGatewayPermission = Permission.builder()
				.principal(new ServicePrincipal("apigateway.amazonaws.com"))
				.build();
		apiLambda.addPermission("API GW Permission", lamdaAPIGatewayPermission);

		System.err.println("ApiStack: added permission to function "+ apiLambda);

		Asset openAPIAsset = Asset.Builder.create(this, "OpenAPIBlogAsset")
				.path("../api/openapi.yaml").build();

		System.err.println("ApiStack: created Asset "+ openAPIAsset);

		Map<String, String> transformMap = new HashMap<String, String>();
		transformMap.put("Location", openAPIAsset.getS3ObjectUrl());
		// Include the OpenAPI template as part of the API Definition supplied to API
		// Gateway
		IResolvable data = Fn.transform("AWS::Include", transformMap);

		InlineApiDefinition apiDefinition = AssetApiDefinition.fromInline(data);

		SpecRestApi restAPI = SpecRestApi.Builder.create(this, "OpenAPIBlogRestAPI")
				.apiDefinition(apiDefinition)
				.restApiName("OpenAPIBlogWidgetAPI")
				.endpointExportName("OpenAPIBlogWidgetRestApiEndpoint")
				.deployOptions(StageOptions.builder().stageName(stage).build())
				.deploy(true)
				.build();

		System.err.println("ApiStack: created SpecRestApi "+ restAPI);

		restIdOutput = CfnOutput.Builder.create(this, "OpenAPIBlogAPIRestIdOutput")
				.value(restAPI.getRestApiId())
				.build();

		// Generate the documentation from the OpenAPI Specification
		// docker build -t openapidoc .
		// docker run -it openapidoc sh
		String entry = "../api/docker";

		// Alpine ruby docker image size = 532.12 MB
		// Ruby slim docker image size = 829.31 MB
		try {
			DockerImage apDocImage = DockerImage.fromBuild(entry);
		}
		catch(RuntimeException ex)
			System.err.println("ApiStack: docker build exception "+ ex);
		
		// TODO: Rather than build the docker image, this should be pushed to a
		// container registry like Amazon ECR and pulled from there.
		// The build of the image can take up to 5 minutes. The image can be referenced
		// from the registry like:
		// DockerImage apDocImage = DockerImage.fromRegistry(path);

		List<String> apiDocPackagingInstructions = Arrays.asList(
				"/bin/sh", "-c",
				"pwd && ls -l && " +
						"widdershins --search false --language_tabs 'javascript:JavaScript' 'python:Python' 'java:Java' --summary openapi.yaml -o /openapi/slate/source/index.html.md && "
						+
						"cd /openapi/slate && " +
						"bundle exec middleman build --clean && " +
						"ls -la build/* && " +
						"cp -a build/. /asset-output/");

		System.err.println("ApiStack: before docker build ");

		BundlingOptions.Builder apiDocBuilderOptions = BundlingOptions.builder().command(apiPackagingInstructions)
				.image(apDocImage)
				.local(new ILocalBundling() {
					@Override
					public @NotNull Boolean tryBundle(@NotNull String outputDir, @NotNull BundlingOptions options) {
						// Use container for all builds.
						return false;
					}
				})
				.user("root")
				.outputType(BundlingOutput.NOT_ARCHIVED);

		System.err.println("ApiStack: after docker build ");

		ISource apiDocSource = Source.asset("../api", AssetOptions.builder()
				.bundling(apiDocBuilderOptions.command(apiDocPackagingInstructions).build())
				.build());

		Bucket webBucket = Bucket.Builder.create(this, "OpenAPIBlogAPIBucket")
				.bucketName(PhysicalName.GENERATE_IF_NEEDED)
				.versioned(true)
				.encryption(BucketEncryption.UNENCRYPTED)
				.autoDeleteObjects(true)
				.removalPolicy(RemovalPolicy.DESTROY)
				.build();

		System.err.println("ApiStack: created bucket "+ webBucket);

		OriginAccessIdentity oai = OriginAccessIdentity.Builder.create(this, "OpenAPIBlogWidgetAPIOAI")
				.comment("OAI for the OpenAPI Blog Widget API Document Website")
				.build();

		S3OriginConfig s3OriginConfig = S3OriginConfig.builder()
				.s3BucketSource(webBucket)
				.originAccessIdentity(oai)
				.build();

		webBucket.grantRead(oai);

		List<SourceConfiguration> cloudFrontConfigs = new ArrayList<SourceConfiguration>();
		cloudFrontConfigs.add(
				SourceConfiguration.builder()
						.s3OriginSource(s3OriginConfig)
						.behaviors(Arrays.asList(
								Behavior.builder()
										.isDefaultBehavior(true)
										.build()))
						.build());

		CloudFrontWebDistribution cloudFrontWebDistribution = CloudFrontWebDistribution.Builder
				.create(this, "OpenAPIBlogCFD")
				.originConfigs(cloudFrontConfigs)
				.build();

		BucketDeployment bucketDeployment = BucketDeployment.Builder.create(this, "OpenAPIBlogS3Deployment")
				.sources(Arrays.asList(apiDocSource))
				.destinationBucket(webBucket)
				.distribution(cloudFrontWebDistribution)
				.distributionPaths(Arrays.asList("/*"))
				.prune(true)
				.cacheControl(Arrays.asList(
						CacheControl.setPublic(),
						CacheControl.maxAge(Duration.seconds(0)),
						CacheControl.sMaxAge(Duration.seconds(0))))
				.build();

		System.err.println("ApiStack: created BucketDeployment "+ bucketDeployment);

		bucketNameOutput = CfnOutput.Builder.create(this, "OpenAPIBlogWebBucketName")
				.value(webBucket.getBucketName())
				.build();

		cloudFrontURLOutput = CfnOutput.Builder.create(this, "OpenAPIBlogCloudFrontURL")
				.value(cloudFrontWebDistribution.getDistributionDomainName())
				.build();

		cloudFrontDistributionIdOutput = CfnOutput.Builder.create(this, "OpenAPIBlogCloudFrontDistributionID")
				.value(cloudFrontWebDistribution.getDistributionId())
				.build();

	}
}
