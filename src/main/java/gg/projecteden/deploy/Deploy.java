package gg.projecteden.deploy;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.SneakyThrows;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import static gg.projecteden.deploy.Option.*;

public class Deploy {
	private static final Map<Option, String> OPTIONS = new HashMap<>();

	private static String pluginDirectory;
	private static String jarPath;
	private static String compileCommand;

	@SneakyThrows
	public static void main(String[] args) {
		OptionParser parser = new OptionParser();
		for (Option option : Option.values())
			option.build(parser);

		final OptionSet parsed = parser.parse(args);

		if (parsed.has(HELP.getArgument())) {
			parser.printHelpOn(System.out);
			return;
		}

		for (Option option : Option.values())
			OPTIONS.put(option, (String) parsed.valueOf(option.getArgument()));

		System.out.println("CONFIGURING...");
		configure();

		System.out.println("COMPILING...");
		compile();

		System.out.println("UPLOADING...");
		upload();

		System.out.println("RELOADING...");
		reload();

		System.out.println("DONE");
		desktopNotification();
	}

	private static void configure() {
		pluginDirectory = "%s/%s/".formatted(OPTIONS.get(WORKSPACE), OPTIONS.get(PLUGIN));

		switch (OPTIONS.get(FRAMEWORK).toLowerCase()) {
			case "gradle" -> {
				compileCommand = "%s clean build".formatted(OPTIONS.get(GRADLE_COMMAND));
				jarPath = OPTIONS.get(GRADLE_BUILD_PATH);
			}
			case "maven" -> {
				compileCommand = "mvn clean package";
				jarPath = OPTIONS.get(MVN_TARGET_PATH);
				if (Boolean.parseBoolean(OPTIONS.get(MVN_OFFLINE)))
					compileCommand += " -o";
				if (Boolean.parseBoolean(OPTIONS.get(MVN_SKIP_TESTS)))
					compileCommand += " -DskipTests";
			}
			default -> throw new RuntimeException("Unsupported framework '" + OPTIONS.get(FRAMEWORK) + "'");
		}
	}

	private static String getReloadCommand() {
		String reloadCommand = "plugman reload " + OPTIONS.get(JAR_NAME);
		if (OPTIONS.get(PLUGIN).startsWith("Nexus"))
			reloadCommand = "nexus reload";

		if (Boolean.parseBoolean(OPTIONS.get(SUDO)))
			reloadCommand = "sudo %s %s".formatted(OPTIONS.get(MC_USER), reloadCommand);

		return reloadCommand;
	}

	private static void compile() {
		System.out.println(bash(compileCommand));
	}

	private static String findCompiledJar() {
		return bash("find %s -iname '%s*.jar' | head -n 1".formatted(pluginDirectory + jarPath, OPTIONS.get(JAR_NAME)));
	}

	private static void upload() {
		System.out.println(bash("scp -P %s %s minecraft@%s:/home/minecraft/servers/%s/plugins/%s.jar".formatted(
			OPTIONS.get(PORT), findCompiledJar(), OPTIONS.get(HOST), OPTIONS.get(SERVER), OPTIONS.get(JAR_NAME)
		)));
	}

	private static void reload() {
		bash("ssh -p %s %s@%s \"mark2 send -n %s '%s'\"".formatted(
			OPTIONS.get(PORT), OPTIONS.get(SSH_USER), OPTIONS.get(HOST), OPTIONS.get(SERVER), getReloadCommand())
		);
	}

	private static void desktopNotification() {
		bash("notify-send -t 2000 -i /home/griffin/Pictures/icons/idea.png \"%s deployment complete\"".formatted(OPTIONS.get(PLUGIN)));
	}

	@SneakyThrows
	public static String bash(String command) {
		System.out.println("Executing command: " + command);
		InputStream result = Runtime.getRuntime().exec(command, null, new File(pluginDirectory)).getInputStream();
		StringBuilder builder = new StringBuilder();
		new Scanner(result).forEachRemaining((string) -> builder.append(string).append(" "));
		return builder.toString().trim();
	}

}
