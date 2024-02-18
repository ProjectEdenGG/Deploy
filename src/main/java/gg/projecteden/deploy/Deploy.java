package gg.projecteden.deploy;

import com.google.common.base.Strings;
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
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.slf4j.impl.SimpleLogger;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static gg.projecteden.deploy.Option.*;

public class Deploy {
	static final Map<Option, String> OPTIONS = new HashMap<>();

	static String pluginDirectory;
	static String jarPath;
	static String compileCommand;
	static String destination;

	static long start = System.currentTimeMillis();
	static UUID id = UUID.randomUUID();
	static boolean completed;

	public static final String ANSI_RESET = "\u001B[0m";
	public static final String ANSI_RED = "\u001B[31m";
	public static final String ANSI_GREEN = "\u001B[32m";
	public static final String ANSI_YELLOW = "\u001B[33m";

	private static void log(String message) {
		System.out.println(ANSI_GREEN + message + ANSI_RESET);
	}

	private static void info(String message) {
		System.out.println(ANSI_YELLOW + message + ANSI_RESET);
	}

	private static class OnShutdown extends Thread {
		@Override
		public void run() {
			if (completed) return;

			log("REPAIRING ORIGINAL JAR...");
			final String message = execRemote("mv %s.old %s".formatted(destination, destination), "minecraft");
			if (!isNullOrEmpty(message))
				System.out.println(message);

			if (Boolean.parseBoolean(OPTIONS.get(DEPLOY_NOTIFICATIONS)))
				mark2("deploy cancel " + id);
		}
	}

	@SneakyThrows
	public static void main(String[] args) {
		Runtime.getRuntime().addShutdownHook(new OnShutdown());

		try {
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

			final Runnable printTime = () -> info(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

			printTime.run();
			log("CONFIGURING...");
			configure();

			log("DELETING...");
			delete();

			log("COMPILING... " + ANSI_RESET + compileCommand);
			compile();

			log("UPLOADING...");
			upload();

			log("RELOADING...");
			reload();

			log("DONE");
			printTime.run();
			desktopNotification();
		} catch (Exception ex) {
			ex.printStackTrace();
		} finally {
			System.exit(0);
		}
	}

	static void configure() {
		pluginDirectory = "%s/%s/".formatted(OPTIONS.get(WORKSPACE), OPTIONS.get(PLUGIN));

		switch (OPTIONS.get(FRAMEWORK).toLowerCase()) {
			case "gradle" -> {
				compileCommand = "%s %sbuild".formatted(OPTIONS.get(GRADLE_COMMAND), (Strings.isNullOrEmpty(OPTIONS.get(MODULE)) ? "" : ":%s:".formatted(OPTIONS.get(MODULE))));
				jarPath = OPTIONS.get(GRADLE_BUILD_PATH);
				if (!isNullOrEmpty(OPTIONS.get(MODULE)))
					jarPath = OPTIONS.get(MODULE) + "/" + jarPath;

				if (Boolean.parseBoolean(OPTIONS.get(COMPILE_OFFLINE)))
					compileCommand += " --offline";
			}
			case "maven" -> {
				compileCommand = "mvn %sclean package".formatted((Strings.isNullOrEmpty(OPTIONS.get(MODULE)) ? "" : "-pl %s -am ".formatted(OPTIONS.get(MODULE))));
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
		if (Boolean.parseBoolean(OPTIONS.get(DEPLOY_NOTIFICATIONS)))
			mark2("deploy create %s %s %s".formatted(id, OPTIONS.get(PLUGIN), OPTIONS.get(MC_USER)));
	}

	static void delete() {
		final String message = execRemote("mv %s %s.old".formatted(destination, destination), "minecraft");
		if (!isNullOrEmpty(message))
			System.out.println(message);
	}

	static void compile() {
		final int exitCode = execLocal(compileCommand).exitValue();
		if (exitCode != 0)
			System.exit(exitCode);
		if (Boolean.parseBoolean(OPTIONS.get(DEPLOY_NOTIFICATIONS)))
			mark2("deploy status %s compiling".formatted(id));
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
		if (Boolean.parseBoolean(OPTIONS.get(DEPLOY_NOTIFICATIONS)))
			mark2("deploy status %s uploading".formatted(id));
		try (SSHClient ssh = new SSHClient()) {
			if (!OPTIONS.get(HOSTS_FILE).isEmpty())
				ssh.loadKnownHosts(Path.of(OPTIONS.get(HOSTS_FILE)).toFile());

			ssh.addHostKeyVerifier(new PromiscuousVerifier());
			ssh.connect(OPTIONS.get(HOST), Integer.parseInt(OPTIONS.get(PORT)));
			ssh.authPublickey("minecraft");
			ssh.useCompression();
			final File from = findCompiledJar().toFile();
			System.out.println("  From: " + from.getAbsolutePath());
			System.out.println("  To: " + destination);
			ssh.newSCPFileTransfer().upload(new FileSystemFile(from), destination);
		}
	}

	static void reload() {
		if (Boolean.parseBoolean(OPTIONS.get(DEPLOY_NOTIFICATIONS)))
			mark2("deploy remove " + id);
		mark2(getReloadCommand());
		completed = true;
	}

	static String getReloadCommand() {
		String reloadCommand = OPTIONS.get(RELOAD_COMMAND).formatted(OPTIONS.get(JAR_NAME));
		if (OPTIONS.get(PLUGIN).matches("Nexus(\\d+)?"))
			if (RELOAD_COMMAND.isDefault(OPTIONS))
				reloadCommand = "nexus reload";

		if (Boolean.parseBoolean(OPTIONS.get(SUDO)))
			reloadCommand = "sudo %s %s".formatted(OPTIONS.get(MC_USER), reloadCommand);

		return reloadCommand;
	}

	static void mark2(String command) {
		final String message = execRemote("mark2 send -n %s '%s'".formatted(OPTIONS.get(SERVER), command), OPTIONS.get(SSH_USER));
		if (!isNullOrEmpty(message))
			System.out.println(message);
	}

	@SneakyThrows
	static void desktopNotification() {
		Notifier notifier = new SendNotification().initNotifier();

		try {
			notifier.send(Notification.builder()
					.title("%s deployment complete".formatted(OPTIONS.get(PLUGIN)))
					// TODO Figure out why it cant load from resources on Windows
					.icon(Icon.create(new URL("https://i.projecteden.gg/idea.png"), "idea"))
					.message("Took %ss".formatted(new DecimalFormat("#.###").format((System.currentTimeMillis() - start) / 1000f)))
					.build());
		} finally {
			notifier.close();
		}
	}

	@SneakyThrows
	static Process execLocal(String command) {
		if (isWindows())
			command = "cmd /c " + command;

		final Process process = new ProcessBuilder(command.split(" "))
				.directory(new File(pluginDirectory))
				.inheritIO()
				.start();

		process.waitFor();
		return process;
	}

	public static boolean isWindows() {
		return System.getProperty("os.name").startsWith("Windows");
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
