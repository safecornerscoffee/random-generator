package com.safecornerscoffee.randomgenerator;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@SpringBootApplication
@RestController
public class RandomGeneratorApplication implements ApplicationContextAware {

	private static File READINESS_FILE = new File("/opt/random-generator-ready");

	private static Random random = new Random();

	private static UUID id = UUID.randomUUID();

	@Value("${version}")
	private String version;

	@Value("${log.file:#{null}}")
	private String logFile;

	@Value("${log.url:#{null}}")
	private String logUrl;

	@Value("${build.type:#{null}}")
	private String buildType;

	@Value("${pattern:None}")
	private String patternName;

	@Value("${seed:0}")
	private long seed;

	private byte[] memoryHole;

	private static Log log = LogFactory.getLog(RandomGeneratorApplication.class);

	private HealthToggleIndicator healthToggle;

	private ApplicationContext applicationContext;

	public RandomGeneratorApplication(HealthToggleIndicator healthToggle) {
		this.healthToggle = healthToggle;
	}

	public static void main(String[] args) throws IOException, InterruptedException {

		waitForPostStart();
		addShutdownHook();
		ready(false);
		delayIfRequested();
		SpringApplication.run(RandomGeneratorApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void dumpInfo() throws IOException {

		Map info = getSysinfo();

		log.info("=== Max Heap Memory:  " + info.get("memory.max") + " MB");
		log.info("=== Used Heap Memory: " + info.get("memory.used") + " MB");
		log.info("=== Free Memory:      " + info.get("memory.free") + " MB");
		log.info("=== Processors:       " + info.get("cpu.procs"));
	}

	@EventListener(ApplicationReadyEvent.class)
	public void createReadinessFile() {
		try {
			ready(true);
		} catch (IOException exp) {
			log.warn("Can't create 'ready' file " + READINESS_FILE +
					" used in readiness check. Possibly running locally, so ignoring it");
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void initSeed() {
		if (seed != 0) {
			random = new Random(seed);
		}
	}

	@RequestMapping(value = "/", produces = "application/json")
	public Map getRandomNumber(@RequestParam(name="burn", required = false) Long burn) throws IOException {
		long start = System.nanoTime();
		Map<String, Object> ret = new HashMap<>();

		burnCpuTimeIfRequested(burn);

		int randomValue = random.nextInt();

		ret.put("random", randomValue);
		ret.put("id", id.toString());

		logRandomValue(randomValue, System.nanoTime() - start);
		return ret;
	}

	@RequestMapping(value = "/memory-eater")
	public void getEatUpMemory(@RequestParam(name = "mb") int megaBytes) {
		memoryHole = new byte[megaBytes * 1024 * 1024];
		random.nextBytes(memoryHole);
	}

	@RequestMapping(value = "/toggle-live")
	public void toggleLiveness() {
		healthToggle.toggle();
	}

	@RequestMapping(value = "/toggle-ready")
	public void toggleReadiness() throws IOException {
		ready(!READINESS_FILE.exists());
	}

	@RequestMapping(value = "/info", produces = "application/json")
	public Map info() throws IOException {
		return getSysinfo();
	}

	@RequestMapping(value = "/shutdown")
	public void shutdown() {
		log.info("SHUTDOWN NOW");
		SpringApplication.exit(applicationContext);
	}

	@RequestMapping(value = "/logs", produces = "text/plain")
	public String logs() throws IOException {
		return getLog();
	}

	private void burnCpuTimeIfRequested(Long burn) {
		if (burn != null) {
			for (int i = 0; i < (burn < 10_000 ? burn : 10_000) * 1_000 * 1_000; i++) {
				random.nextInt();
			}
		}
	}

	private void logRandomValue(int randomValue, long duration) throws IOException {
		if (logFile != null) {
			logToFile(logFile, randomValue, duration);
		}

		if (logUrl != null) {
			logToUrl(new URL(logUrl), randomValue, duration);
		}
	}

	private void logToFile(String file, int randomValue, long duration) throws IOException {
		String date = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
				.withZone(ZoneOffset.UTC)
				.format(Instant.now());
		String line = date + "," + id + "," + duration + "," + randomValue + "\n";
		IoUtils.appendLineWithLocking(file, line);
	}

	private void logToUrl(URL url, int randomValue, long duration) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");

		String output = String.format(
				"{ \"id\": \"%s\", \"random\": \"%d\", \"duration\": \"%d\" }",
				id, randomValue, duration);

		log.info("Sending log to " + url);
		connection.setDoOutput(true);
		OutputStream os = connection.getOutputStream();
		os.write(output.getBytes());
		os.flush();
		os.close();

		int responseCode = connection.getResponseCode();
		log.info("Log delegate response: " + responseCode);
	}

	private static void delayIfRequested() throws InterruptedException {
		int sleep = Integer.parseInt(System.getenv().getOrDefault("DELAY_STARTUP", "0"));
		if (sleep > 0) {
			Thread.sleep(sleep * 1000);
		}
	}

	private String getLog() throws IOException {
		if (logFile == null) {
			return "";
		}
		return String.join("\n", Files.readAllLines(Paths.get(logFile)));
	}

	private Map getSysinfo() throws IOException {
		Map ret = new HashMap();
		Runtime rt = Runtime.getRuntime();
		int mb = 1024  * 1024;
		ret.put("memory.max", rt.maxMemory() / mb);
		ret.put("memory.used", rt.totalMemory() / mb);
		ret.put("memory.free", rt.freeMemory() / mb);
		ret.put("cpu.procs", rt.availableProcessors());
		ret.put("id", id);
		ret.put("version", version);
		ret.put("pattern", patternName);
		if (logFile != null) {
			ret.put("logFile", logFile);
		}
		if (logUrl != null) {
			ret.put("logUrl", logUrl);
		}
		if (seed != 0) {
			ret.put("seed", seed);
		}
		if (buildType != null) {
			ret.put("build-type", buildType);
		}

		// From Downward API environment
		Map<String, String> env = System.getenv();
		for (String key : new String[] { "POD_IP", "NODE_NAME"}) {
			if (env.containsKey(key)) {
				ret.put(key, env.get(key));
			}
		}

		// From Downward API mount
		File podInfoDir = new File("/pod-info");
		if (podInfoDir.exists() && podInfoDir.isDirectory()) {
			for (String meta : new String[] { "labels", "annotations"}) {
				File file = new File(podInfoDir, meta);
				if (file.exists()) {
					byte[] encoded = Files.readAllBytes(file.toPath());
					ret.put(file.getName(), new String(encoded));
				}
			}
		}

		// Add environment
		ret.put("env", System.getenv());

		return ret;
	}

	static private void ready(boolean create) throws IOException {
		if (create) {
			new FileOutputStream(READINESS_FILE).close();
		} else {
			if (READINESS_FILE.exists()) {
				READINESS_FILE.delete();
			}
		}
	}

	private static void waitForPostStart() throws IOException, InterruptedException {
		if ("true".equals(System.getenv("WAIT_FOR_POST_START"))) {
			File postStartFile = new File("/opt/postStart-done");
			while (!postStartFile.exists()) {
				log.info("Waiting for postStart to be finished ....");
				Thread.sleep(10_000);
			}
			log.info("postStart Message: " + new String(Files.readAllBytes(postStartFile.toPath())));
		} else {
			log.info("No WAIT_FOR_POST_START configured");
		}
	}

	private static void addShutdownHook() {
		Runtime.getRuntime().addShutdownHook(
				new Thread(
						() -> log.info(">>>> SHUTDOWN HOOK called. Possibly because of a SIGTERM from Kubernetes")));
	}


	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
}
