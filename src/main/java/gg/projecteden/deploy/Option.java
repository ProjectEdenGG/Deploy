package gg.projecteden.deploy;

import gg.projecteden.deploy.annotations.DefaultValue;
import gg.projecteden.deploy.annotations.Description;
import gg.projecteden.deploy.annotations.Required;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import lombok.SneakyThrows;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;

import static gg.projecteden.deploy.Deploy.isWindows;

public enum Option {
	@Description("Print this help menu")
	HELP,

	@Required
	@Description("SSH user to execute commands with")
	SSH_USER,

	@Required
	@Description("Minecraft user to execute commands with")
	MC_USER,

	@Required
	@Description("Project folder name")
	PLUGIN,

	@Required
	@Description("Minecraft server to deploy to")
	SERVER,

	@Description("Control whether the plugin is reloaded with console or by sudoing the minecraft account")
	@DefaultValue("true")
	SUDO,

	@Description("Hostname of the server")
	@DefaultValue("server.projecteden.gg")
	HOST,

	@Description("SSH Port")
	@DefaultValue("9802")
	PORT,

	@Description("Known hosts file")
	@DefaultValue("")
	HOSTS_FILE,

	@Description("Reload command")
	@DefaultValue("plugman reload %s")
	RELOAD_COMMAND,

	@Description("Jar name, if different than folder name")
	JAR_NAME,

	@Required
	@Description("Workspace containing the plugin folder")
	WORKSPACE,

	@Required
	@Description("Maven or Gradle")
	FRAMEWORK,

	@Description("Compile in offline mode")
	@DefaultValue("true")
	COMPILE_OFFLINE,

	@Description("Skip tests with Maven")
	@DefaultValue("false")
	MVN_SKIP_TESTS,

	@Description("Folder where the compiled jar resides with Maven")
	@DefaultValue("target")
	MVN_TARGET_PATH,

	@Description("Gradle command")
	GRADLE_COMMAND {
		@Override
		public String getDefaultValue() {
			if (isWindows())
				return "gradlew.bat";
			else
				return "./gradlew";
		}

		@Override
		public boolean hasDefaultValue() {
			return true;
		}
	},

	@Description("Folder where the compiled jar resides with Gradle")
	@DefaultValue("build/libs")
	GRADLE_BUILD_PATH,
	;

	public String getArgument() {
		return name().toLowerCase().replaceAll("_", "-");
	}

	@SneakyThrows
	public Field getField() {
		return getClass().getField(name());
	}

	public boolean isRequired() {
		return getField().getAnnotation(Required.class) != null;
	}

	public String getDescription() {
		final Description annotation = getField().getAnnotation(Description.class);
		if (annotation == null)
			return null;

		return annotation.value();
	}

	public boolean hasDefaultValue() {
		final DefaultValue annotation = getField().getAnnotation(DefaultValue.class);
		return annotation != null && !annotation.value().isEmpty();
	}

	public String getDefaultValue() {
		final DefaultValue annotation = getField().getAnnotation(DefaultValue.class);
		if (annotation == null)
			return null;

		return annotation.value();
	}

	public boolean isDefault(Map<Option, String> options) {
		return Objects.equals(options.get(this), getDefaultValue());
	}

	public void build(OptionParser parser) {
		var arg = parser.accepts(getArgument(), getDescription());

		final ArgumentAcceptingOptionSpec<String> spec;
		if (isRequired()) {
			spec = arg.withRequiredArg();
		} else
			spec = arg.withOptionalArg();

		if (hasDefaultValue())
			spec.defaultsTo(Objects.requireNonNull(getDefaultValue()));
	}

	public static void buildAll(OptionParser parser) {
		for (Option option : Option.values())
			option.build(parser);
	}
}
