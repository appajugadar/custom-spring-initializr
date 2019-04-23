package com.spring.boot.custom.initilizr;

import io.spring.initializr.generator.ProjectGenerator;
import io.spring.initializr.generator.ProjectRequest;
import io.spring.initializr.generator.ProjectRequestResolver;
import io.spring.initializr.generator.ProjectResourceLocator;
import io.spring.initializr.metadata.BillOfMaterials;
import io.spring.initializr.metadata.Dependency;
import io.spring.initializr.metadata.InitializrMetadataProvider;
import io.spring.initializr.util.TemplateRenderer;
import io.spring.initializr.util.Version;
import io.spring.initializr.util.VersionProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.*;

public class CustomProjectGenerator extends ProjectGenerator {

    private static final Version VERSION_2_0_0 = Version.parse("2.0.0.RELEASE");

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private InitializrMetadataProvider metadataProvider;

    @Autowired
    private ProjectRequestResolver requestResolver;

    @Autowired
    private TemplateRenderer templateRenderer = new TemplateRenderer();

    @Autowired
    private ProjectResourceLocator projectResourceLocator = new ProjectResourceLocator();

    @Value("${TMPDIR:.}/initializr")
    private String tmpdir;

    private File temporaryDirectory;

    private transient Map<String, List<File>> temporaryFiles = new LinkedHashMap();

    public CustomProjectGenerator() {
    }

    private static boolean isGradleBuild(ProjectRequest request) {
        return "gradle".equals(request.getBuild());
    }

    private static boolean isGradle4Available(Version bootVersion) {
        return VERSION_2_0_0.compareTo(bootVersion) <= 0;
    }

    public InitializrMetadataProvider getMetadataProvider() {
        return this.metadataProvider;
    }

    public void setMetadataProvider(InitializrMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void setRequestResolver(ProjectRequestResolver requestResolver) {
        this.requestResolver = requestResolver;
    }

    public void setTemplateRenderer(TemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
    }

    public void setProjectResourceLocator(ProjectResourceLocator projectResourceLocator) {
        this.projectResourceLocator = projectResourceLocator;
    }

    protected File generateProjectStructure(ProjectRequest request, Map<String, Object> model) {
        File rootDir;
        try {
            rootDir = File.createTempFile("tmp", "", this.getTemporaryDirectory());
        } catch (IOException var12) {
            throw new IllegalStateException("Cannot create temp dir", var12);
        }

        this.addTempFile(rootDir.getName(), rootDir);
        rootDir.delete();
        rootDir.mkdirs();
        File dir = this.initializerProjectDir(rootDir, request);
        String applicationName;
        String language;
        if (isGradleBuild(request)) {
            applicationName = new String(this.doGenerateGradleBuild(model));
            this.writeText(new File(dir, "build.gradle"), applicationName);
            language = new String(this.doGenerateGradleSettings(model));
            this.writeText(new File(dir, "settings.gradle"), language);
            this.writeGradleWrapper(dir, Version.safeParse(request.getBootVersion()));
        } else {
            applicationName = new String(this.doGenerateMavenPom(model));
            this.writeText(new File(dir, "pom.xml"), applicationName);
            this.writeMavenWrapper(dir);
        }

        this.generateGitIgnore(dir, request);
        applicationName = request.getApplicationName();
        language = request.getLanguage();
        File src = new File(new File(dir, "src/main/" + language), request.getPackageName().replace(".", "/"));
        src.mkdirs();
        String extension = "kotlin".equals(language) ? "kt" : language;
        this.write(new File(src, applicationName + "." + extension), "Application." + extension, model);
        if ("war".equals(request.getPackaging())) {
            String fileName = "ServletInitializer." + extension;
            this.write(new File(src, fileName), fileName, model);
        }

        File test = new File(new File(dir, "src/test/" + language), request.getPackageName().replace(".", "/"));
        test.mkdirs();
        this.setupTestModel(request, model);
        this.write(new File(test, applicationName + "Tests." + extension), "ApplicationTests." + extension, model);
        File resources = new File(dir, "src/main/resources");
        resources.mkdirs();
        this.writeText(new File(resources, "application.properties"), "");
        if (request.hasWebFacet()) {
            (new File(dir, "src/main/resources/templates")).mkdirs();
            (new File(dir, "src/main/resources/static")).mkdirs();
        }


        //some custom stuff

        for (Dependency dependency : request.getResolvedDependencies()) {
            if (dependency.getArtifactId().equals("spring-boot-starter-web")) {
                this.write(new File(test, applicationName + "MyTests." + extension), "ApplicationTests." + extension, model);
            }
        }
        return rootDir;
    }

    private void addTempFile(String group, File file) {
        ((List) this.temporaryFiles.computeIfAbsent(group, (key) -> {
            return new ArrayList();
        })).add(file);
    }

    private File getTemporaryDirectory() {
        if (this.temporaryDirectory == null) {
            this.temporaryDirectory = new File(this.tmpdir, "initializr");
            this.temporaryDirectory.mkdirs();
        }
        return this.temporaryDirectory;
    }


    private Map<String, String> toBomModel(ProjectRequest request, BillOfMaterials bom) {
        Map<String, String> model = new HashMap();
        model.put("groupId", bom.getGroupId());
        model.put("artifactId", bom.getArtifactId());
        model.put("versionToken", bom.getVersionProperty() != null ? "${" + this.computeVersionProperty(request, bom.getVersionProperty()) + "}" : bom.getVersion());
        return model;
    }

    private String computeVersionProperty(ProjectRequest request, VersionProperty property) {
        return isGradleBuild(request) && property.isInternal() ? property.toCamelCaseFormat() : property.toStandardFormat();
    }

    private byte[] doGenerateMavenPom(Map<String, Object> model) {
        return this.templateRenderer.process("starter-pom.xml", model).getBytes();
    }

    private byte[] doGenerateGradleBuild(Map<String, Object> model) {
        return this.templateRenderer.process("starter-build.gradle", model).getBytes();
    }

    private byte[] doGenerateGradleSettings(Map<String, Object> model) {
        return this.templateRenderer.process("starter-settings.gradle", model).getBytes();
    }

    private void writeGradleWrapper(File dir, Version bootVersion) {
        String gradlePrefix = isGradle4Available(bootVersion) ? "gradle4" : "gradle3";
        this.writeTextResource(dir, "gradlew.bat", gradlePrefix + "/gradlew.bat");
        this.writeTextResource(dir, "gradlew", gradlePrefix + "/gradlew");
        File wrapperDir = new File(dir, "gradle/wrapper");
        wrapperDir.mkdirs();
        this.writeTextResource(wrapperDir, "gradle-wrapper.properties", gradlePrefix + "/gradle/wrapper/gradle-wrapper.properties");
        this.writeBinaryResource(wrapperDir, "gradle-wrapper.jar", gradlePrefix + "/gradle/wrapper/gradle-wrapper.jar");
    }

    private void writeMavenWrapper(File dir) {
        this.writeTextResource(dir, "mvnw.cmd", "maven/mvnw.cmd");
        this.writeTextResource(dir, "mvnw", "maven/mvnw");
        File wrapperDir = new File(dir, ".mvn/wrapper");
        wrapperDir.mkdirs();
        this.writeTextResource(wrapperDir, "maven-wrapper.properties", "maven/wrapper/maven-wrapper.properties");
        this.writeBinaryResource(wrapperDir, "maven-wrapper.jar", "maven/wrapper/maven-wrapper.jar");
    }

    private File writeBinaryResource(File dir, String name, String location) {
        return this.doWriteProjectResource(dir, name, location, true);
    }

    private File writeTextResource(File dir, String name, String location) {
        return this.doWriteProjectResource(dir, name, location, false);
    }

    private File doWriteProjectResource(File dir, String name, String location, boolean binary) {
        File target = new File(dir, name);
        if (binary) {
            this.writeBinary(target, this.projectResourceLocator.getBinaryResource("classpath:project/" + location));
        } else {
            this.writeText(target, this.projectResourceLocator.getTextResource("classpath:project/" + location));
        }

        return target;
    }

    private void writeText(File target, String body) {
        try {
            OutputStream stream = new FileOutputStream(target);
            Throwable var4 = null;
            try {
                StreamUtils.copy(body, Charset.forName("UTF-8"), stream);
            } catch (Throwable var14) {
                var4 = var14;
                throw var14;
            } finally {
                if (stream != null) {
                    if (var4 != null) {
                        try {
                            stream.close();
                        } catch (Throwable var13) {
                            var4.addSuppressed(var13);
                        }
                    } else {
                        stream.close();
                    }
                }

            }

        } catch (Exception var16) {
            throw new IllegalStateException("Cannot write file " + target, var16);
        }
    }

    private void writeBinary(File target, byte[] body) {
        try {
            OutputStream stream = new FileOutputStream(target);
            Throwable var4 = null;

            try {
                StreamUtils.copy(body, stream);
            } catch (Throwable var14) {
                var4 = var14;
                throw var14;
            } finally {
                if (stream != null) {
                    if (var4 != null) {
                        try {
                            stream.close();
                        } catch (Throwable var13) {
                            var4.addSuppressed(var13);
                        }
                    } else {
                        stream.close();
                    }
                }
            }
        } catch (Exception var16) {
            throw new IllegalStateException("Cannot write file " + target, var16);
        }
    }

    private File initializerProjectDir(File rootDir, ProjectRequest request) {
        if (request.getBaseDir() != null) {
            File dir = new File(rootDir, request.getBaseDir());
            dir.mkdirs();
            return dir;
        } else {
            return rootDir;
        }
    }

}