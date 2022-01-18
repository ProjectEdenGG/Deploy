package gg.projecteden.deploy;

import fr.jcgay.notification.Icon;
import fr.jcgay.notification.Notification;
import fr.jcgay.notification.Notifier;
import fr.jcgay.notification.SendNotification;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.SneakyThrows;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.slf4j.impl.SimpleLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gg.projecteden.deploy.Option.COMPILE_OFFLINE;
import static gg.projecteden.deploy.Option.FRAMEWORK;
import static gg.projecteden.deploy.Option.GRADLE_BUILD_PATH;
import static gg.projecteden.deploy.Option.GRADLE_COMMAND;
import static gg.projecteden.deploy.Option.HELP;
import static gg.projecteden.deploy.Option.HOST;
import static gg.projecteden.deploy.Option.JAR_NAME;
import static gg.projecteden.deploy.Option.MC_USER;
import static gg.projecteden.deploy.Option.MVN_SKIP_TESTS;
import static gg.projecteden.deploy.Option.MVN_TARGET_PATH;
import static gg.projecteden.deploy.Option.PLUGIN;
import static gg.projecteden.deploy.Option.PORT;
import static gg.projecteden.deploy.Option.SERVER;
import static gg.projecteden.deploy.Option.SSH_USER;
import static gg.projecteden.deploy.Option.SUDO;
import static gg.projecteden.deploy.Option.WORKSPACE;

public class Deploy {
	static final Map<Option, String> OPTIONS = new HashMap<>();

	static String pluginDirectory;
	static String jarPath;
	static String compileCommand;
	static String destination;

	static long start = System.currentTimeMillis();

	public static final String ANSI_RESET = "\u001B[0m";
	public static final String ANSI_RED = "\u001B[31m";
	public static final String ANSI_GREEN = "\u001B[32m";
	public static final String ANSI_YELLOW = "\u001B[33m";

	private static void log(String message) {
		System.out.println(ANSI_GREEN + message + ANSI_RESET);
	}

	@SneakyThrows
	public static void main(String[] args) {
		System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "Warn");

		OptionParser parser = new OptionParser();
		Option.buildAll(parser);

		final OptionSet parsed = parser.parse(args);

		if (parsed.has(HELP.getArgument())) {
			parser.printHelpOn(System.out);
			return;
		}

		for (Option option : Option.values())
			OPTIONS.put(option, (String) parsed.valueOf(option.getArgument()));

		log("CONFIGURING...");
		configure();

		log("DELETING...");
		delete();

		log("COMPILING...");
		compile();

		log("UPLOADING...");
		upload();

		log("RELOADING...");
		reload();

		log("DONE");
		desktopNotification();

		System.exit(0);
	}

	static void configure() {
		pluginDirectory = "%s/%s/".formatted(OPTIONS.get(WORKSPACE), OPTIONS.get(PLUGIN));

		switch (OPTIONS.get(FRAMEWORK).toLowerCase()) {
			case "gradle" -> {
				compileCommand = "%s jar".formatted(OPTIONS.get(GRADLE_COMMAND));
				jarPath = OPTIONS.get(GRADLE_BUILD_PATH);

				if (Boolean.parseBoolean(OPTIONS.get(COMPILE_OFFLINE)))
					compileCommand += " --offline";
			}
			case "maven" -> {
				compileCommand = "mvn clean package";
				jarPath = OPTIONS.get(MVN_TARGET_PATH);
				if (Boolean.parseBoolean(OPTIONS.get(COMPILE_OFFLINE)))
					compileCommand += " -o";
				if (Boolean.parseBoolean(OPTIONS.get(MVN_SKIP_TESTS)))
					compileCommand += " -DskipTests";
			}
			default -> throw new RuntimeException("Unsupported framework '" + OPTIONS.get(FRAMEWORK) + "'");
		}

		if (isNullOrEmpty(OPTIONS.get(JAR_NAME)))
			OPTIONS.put(JAR_NAME, OPTIONS.get(PLUGIN));

		destination = "/home/minecraft/servers/%s/plugins/%s.jar".formatted(OPTIONS.get(SERVER), OPTIONS.get(JAR_NAME));
	}

	static void delete() {
		final String message = execRemote("rm " + destination, "minecraft");
		if (!isNullOrEmpty(message))
			System.out.println(message);
	}

	static void compile() {
		final int exitCode = execLocal(compileCommand).exitValue();
		if (exitCode != 0)
			System.exit(exitCode);
	}

	@SneakyThrows
	static Path findCompiledJar() {
		List<Path> matches = new ArrayList<>();
		Files.walk(Path.of(pluginDirectory + jarPath)).forEach(path -> {
			if (!path.toFile().isFile())
				return;

			if (!path.toUri().toString().endsWith(".jar"))
				return;

			matches.add(path);
		});

		if (matches.isEmpty())
			throw new RuntimeException("Could not locate compiled jar in " + pluginDirectory + jarPath);

		// Find jar with the shortest name
		matches.sort(Comparator.comparingInt(path -> path.toUri().toString().length()));

		return matches.get(0);
	}

	@SneakyThrows
	static void upload() {
		try (SSHClient ssh = new SSHClient()) {
			ssh.loadKnownHosts();
			ssh.connect(OPTIONS.get(HOST), Integer.parseInt(OPTIONS.get(PORT)));
			ssh.authPublickey("minecraft");
			ssh.useCompression();
			ssh.newSCPFileTransfer().upload(new FileSystemFile(findCompiledJar().toFile()), destination);
		}
	}

	static void reload() {
		final String message = execRemote("mark2 send -n %s '%s'".formatted(OPTIONS.get(SERVER), getReloadCommand()), OPTIONS.get(SSH_USER));
		if (!isNullOrEmpty(message))
			System.out.println(message);
	}

	static String getReloadCommand() {
		String reloadCommand = "plugman reload " + OPTIONS.get(JAR_NAME);
		if (OPTIONS.get(PLUGIN).startsWith("Nexus"))
			reloadCommand = "nexus reload";

		if (Boolean.parseBoolean(OPTIONS.get(SUDO)))
			reloadCommand = "sudo %s %s".formatted(OPTIONS.get(MC_USER), reloadCommand);

		return reloadCommand;
	}

	@SneakyThrows
	static void desktopNotification() {
		Notifier notifier = new SendNotification().initNotifier();

		try {
			notifier.send(Notification.builder()
					.title("%s deployment complete".formatted(OPTIONS.get(PLUGIN)))
					.icon(Icon.create(Path.of("idea.png").toUri().toURL(), "idea"))
					.message("Took %ss".formatted(new DecimalFormat("#.###").format((System.currentTimeMillis() - start) / 1000f)))
					.build());
		} finally {
			notifier.close();
		}
	}

	@SneakyThrows
	static Process execLocal(String command) {
		final Process process = new ProcessBuilder(command.split(" "))
				.directory(new File(pluginDirectory))
				.inheritIO()
				.start();
		process.waitFor();
		return process;
	}

	@SneakyThrows
	static String execRemote(String command, String username) {
		try (SSHClient ssh = new SSHClient()) {
			ssh.loadKnownHosts();
			ssh.connect(OPTIONS.get(HOST), Integer.parseInt(OPTIONS.get(PORT)));
			ssh.authPublickey(username);

			try (Session session = ssh.startSession()) {
				final Command cmd = session.exec(command);
				return IOUtils.readFully(cmd.getInputStream()).toString();
			}
		}
	}

	private static boolean isNullOrEmpty(String s) {
		return s == null || s.isEmpty();
	}

}
