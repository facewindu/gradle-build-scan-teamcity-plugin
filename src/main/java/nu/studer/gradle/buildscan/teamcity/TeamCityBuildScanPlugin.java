package nu.studer.gradle.buildscan.teamcity;

import com.gradle.scan.plugin.BuildScanExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.service.ServiceLookupException;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.UnknownServiceException;
import org.gradle.util.GradleVersion;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;

@SuppressWarnings("unused")
public class TeamCityBuildScanPlugin implements Plugin<Object> {

    private static final String TEAMCITY_VERSION_ENV = "TEAMCITY_VERSION";
    private static final String GRADLE_BUILDSCAN_TEAMCITY_PLUGIN_ENV = "GRADLE_BUILDSCAN_TEAMCITY_PLUGIN";
    private static final String BUILD_SCAN_PLUGIN_ID = "com.gradle.build-scan";
    private static final String GRADLE_ENTERPRISE_PLUGIN_ID = "com.gradle.enterprise";
    private static final String BUILD_SCAN_SERVICE_MESSAGE_NAME = "nu.studer.teamcity.buildscan.buildScanLifeCycle";
    private static final String BUILD_SCAN_SERVICE_STARTED_MESSAGE_ARGUMENT = "BUILD_STARTED";
    private static final String BUILD_SCAN_SERVICE_URL_MESSAGE_ARGUMENT_PREFIX = "BUILD_SCAN_URL:";

    @Override
    public void apply(@Nonnull Object object) {
        // abort if old Gradle version is not supported
        if (GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("5.0")) < 0) {
            throw new IllegalStateException("This version of the TeamCity build scan plugin is not compatible with Gradle < 5.0");
        }

        // do not register callback if this is not a TeamCity build or we are using the TeamCity Gradle build runner with the TC build scan plugin applied
        if (System.getenv(TEAMCITY_VERSION_ENV) == null || System.getenv(GRADLE_BUILDSCAN_TEAMCITY_PLUGIN_ENV) != null) {
            return;
        }

        // handle plugin application to settings file and project file
        if (object instanceof Settings) {
            Settings settings = (Settings) object;
            init(settings);
        } else if (object instanceof Project) {
            Project project = (Project) object;
            init(project);
        } else {
            throw new IllegalStateException("The TeamCity build scan plugin can only be applied to Settings and Project instances");
        }
    }

    private void init(Settings settings) {
        settings.getGradle().settingsEvaluated(s -> {
            LoggingController logging = new LoggingController(settings.getGradle());

            logging.log(ServiceMessage.of(BUILD_SCAN_SERVICE_MESSAGE_NAME, BUILD_SCAN_SERVICE_STARTED_MESSAGE_ARGUMENT).toString());

            settings.getPluginManager().withPlugin(GRADLE_ENTERPRISE_PLUGIN_ID, appliedPlugin -> {
                BuildScanExtension buildScanExtension = settings.getExtensions().getByType(BuildScanExtension.class);
                if (supportsBuildScanPublishedListener(buildScanExtension)) {
                    buildScanExtension.buildScanPublished(publishedBuildScan -> {
                            ServiceMessage serviceMessage = ServiceMessage.of(
                                BUILD_SCAN_SERVICE_MESSAGE_NAME,
                                BUILD_SCAN_SERVICE_URL_MESSAGE_ARGUMENT_PREFIX + publishedBuildScan.getBuildScanUri().toString()
                            );
                            logging.log(serviceMessage.toString());
                        }
                    );
                }
            });
        });
    }

    private void init(Project project) {
        LoggingController logging = new LoggingController(project.getGradle());

        logging.log(ServiceMessage.of(BUILD_SCAN_SERVICE_MESSAGE_NAME, BUILD_SCAN_SERVICE_STARTED_MESSAGE_ARGUMENT).toString());

        project.getPluginManager().withPlugin(BUILD_SCAN_PLUGIN_ID, appliedPlugin -> {
            BuildScanExtension buildScanExtension = project.getExtensions().getByType(BuildScanExtension.class);
            if (supportsBuildScanPublishedListener(buildScanExtension)) {
                buildScanExtension.buildScanPublished(publishedBuildScan -> {
                        ServiceMessage serviceMessage = ServiceMessage.of(
                            BUILD_SCAN_SERVICE_MESSAGE_NAME,
                            BUILD_SCAN_SERVICE_URL_MESSAGE_ARGUMENT_PREFIX + publishedBuildScan.getBuildScanUri().toString()
                        );
                        logging.log(serviceMessage.toString());
                    }
                );
            }
        });
    }

    private static boolean supportsBuildScanPublishedListener(BuildScanExtension extension) {
        Class<?> clazz = extension.getClass();
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals("buildScanPublished")) {
                return true;
            }
        }
        return false;
    }

    private static final class LoggingController {

        private final StyledTextOutput styledTextOutput;

        private LoggingController(Gradle gradle) {
            StyledTextOutputFactory styledTextOutputFactory = ServicesFactory.get(gradle, StyledTextOutputFactory.class);
            this.styledTextOutput = styledTextOutputFactory.create(TeamCityBuildScanPlugin.class, LogLevel.QUIET);
        }

        private void log(String msg) {
            styledTextOutput.println(msg);
        }

    }

    private static final class ServicesFactory {

        private static <T> T get(Gradle gradle, Class<T> type) {
            return get(((GradleInternal) gradle).getServices(), type);
        }

        private static <T> T get(ServiceRegistry services, Class<T> type) {
            T service = maybeGet(services, type);
            if (service == null) {
                throw new IllegalStateException(String.format("Failed to load service '%s'", type));
            }
            return service;
        }

        private static <T> T maybeGet(ServiceRegistry services, Class<T> type) {
            try {
                return services.get(type);
            } catch (UnknownServiceException | ServiceLookupException e) {
                return null;
            }
        }

    }

}
