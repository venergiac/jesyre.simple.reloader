package com.jesyre.reloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.util.Properties;

public class NodeMain {

	public static Object instance;

	public static boolean runLoop = false;

	public static String libPrefix = "<<<<THE PREFIX LIB>>>>";
	public static String libPath = "lib";
	public static String upgradeLibPath = "upgrade";
	public static String upgradeLibFile = "upgrade.properties";

	public static String confPath = "conf";
	public static String mainClass = "<<<<THE MAIN CLASS>>>>";
	public static String mainMethodStart = "startInstance";
	public static String mainMethodStop = "stopInstance";

	public static URLClassLoader startClassloader(String basePath, String[] args) throws Exception {

		File file = new File(basePath, libPath);
		System.out.println(file);
		String[] jars = file.list();
		URL[] jarUrls = new URL[jars.length + 1];
		int i = 0;
		for (String jar : jars) {
			File fileJar = new File(file, jar);
			jarUrls[i++] = fileJar.toURI().toURL();
			System.out.println(fileJar);
		}
		jarUrls[i] = new File(basePath, confPath).toURI().toURL();

		URLClassLoader classLoader = new URLClassLoader(jarUrls, NodeMain.class.getClassLoader());
		Thread.currentThread().setContextClassLoader(classLoader);

		Class classToLoad = Class.forName(mainClass, true, classLoader);

		instance = classToLoad.newInstance();

		Method method = classToLoad.getDeclaredMethod(mainMethodStart, args.getClass());
		Object result = method.invoke(instance, (Object) args);

		return classLoader;
	}

	public static void stopClassloader(URLClassLoader classLoader) throws Exception {
		System.out.println("stopping...");
		Method method = instance.getClass().getDeclaredMethod(mainMethodStop);
		Object result = method.invoke(instance);
		System.out.println("...stopped");
		System.out.println(result);
		Thread.sleep(10000);
		classLoader.close();
		System.gc();

	}

	public static URLClassLoader checkUpgradeByProperies(final String basePath, final String args[], URLClassLoader classLoader) throws Exception {
		File upgradeLib = new File(basePath, upgradeLibPath);
		File upgradeLibFileProperties = new File(upgradeLib, upgradeLibFile);

		if (upgradeLibFileProperties.exists()) {

			Properties props = new Properties();

			try (FileInputStream in = new FileInputStream(upgradeLibFileProperties)) {
				props.load(in);
			}

			String filesToAdd = props.getProperty("add");
			String filesToRemove = props.getProperty("del");

			File lib = new File(basePath, libPath);

			if (filesToAdd != null && filesToRemove != null) {

				// stopping
				stopClassloader(classLoader);

				String[] files = filesToAdd.split(",");

				for (String file : files) {

					File f = new File(lib, file);
					f.setWritable(true);
					boolean deleted = f.delete();
					if (!deleted) {
						throw new Exception("cannot delete " + f);
					}
				}
				files = filesToRemove.split(",");

				for (String file : files) {

					File f = new File(lib, file);
					copyFile(f, new File(lib, f.getName()));
				}

				classLoader = startClassloader(basePath, args);

			}

		}

		return classLoader;

	}

	public static URLClassLoader checkSimpleUpgrade(String basePath, String args[], URLClassLoader classLoader) throws Exception {

		FilenameFilter rmdFilter = new FilenameFilter() {
			public boolean accept(File dir, String name) {
				String lowercaseName = name.toLowerCase();
				if (lowercaseName.startsWith(libPrefix)) {
					return true;
				} else {
					return false;
				}
			}
		};

		File lib = new File(basePath, libPath);
		long lastModified = 0;

		File[] currentFiles = lib.listFiles(rmdFilter);
		for (File file : currentFiles) {
			lastModified = Math.max(lastModified, file.lastModified());
		}

		File fileToReplace = null;

		File upgradeLib = new File(basePath, upgradeLibPath);
		File[] files = upgradeLib.listFiles(rmdFilter);
		for (File file : files) {

			if (file.lastModified() > lastModified) {
				lastModified = file.lastModified();
				fileToReplace = file;
			}
		}

		if (fileToReplace == null) {
			return classLoader;
		}

		// stopping
		stopClassloader(classLoader);

		for (File file : currentFiles) {

			file.setWritable(true);
			boolean deleted = file.renameTo(new File(file.getName() + ".bck"));
			if (!deleted) {
				throw new Exception("cannot upgrade to " + fileToReplace);
			}
		}

		System.out.println("UPGRADING: " + fileToReplace + " -> " + lib);

		copyFile(fileToReplace, new File(lib, fileToReplace.getName()));

		classLoader = startClassloader(basePath, args);

		return classLoader;
	}

	public static void copyFile(File sourceFile, File destFile) throws IOException {
		if (!destFile.exists()) {
			destFile.createNewFile();
		}

		FileInputStream sourceStream = null;
		FileOutputStream destinationStream = null;
		FileChannel source = null;
		FileChannel destination = null;
		try {
			sourceStream = new FileInputStream(sourceFile);
			source = sourceStream.getChannel();
			destinationStream = new FileOutputStream(destFile);
			destination = destinationStream.getChannel();

			// previous code: destination.transferFrom(source, 0,
			// source.size());
			// to avoid infinite loops, should be:
			long count = 0;
			long size = source.size();
			while ((count += destination.transferFrom(source, count, size - count)) < size)
				;
		} finally {
			if (sourceStream != null) {
				sourceStream.close();
			}
			if (destinationStream != null) {
				destinationStream.close();
			}
			if (source != null) {
				source.close();
			}
			if (destination != null) {
				destination.close();
			}
		}
	}

	public static void start(String[] args) throws Exception {

		String basePath = null;

		File baseJar = new File(NodeMain.class.getProtectionDomain().getCodeSource().getLocation().getPath());

		File base = new File(baseJar.getParent());
		if (base.getAbsolutePath().endsWith("bin")) {
			basePath = base.getParent();
		} else if (base.getAbsolutePath().endsWith("lib")) {
			basePath = base.getParent();
		} else {
			basePath = base.getAbsolutePath();
		}

		System.out.println("assuming basePath " + basePath);

		URLClassLoader classLoader = startClassloader(basePath, args);
		runLoop = true;

		// stay pending
		int i = 0;
		while (runLoop) {
			Thread.sleep(10000);
			if (i++ > 10) {
				classLoader = checkSimpleUpgrade(basePath, args, classLoader);
				i = 0;
			}

		}

		// stopping
		stopClassloader(classLoader);

		System.exit(0);

	}

	public static void stop(String[] args) {
		runLoop = false;
	}

	/**
	 * Main
	 * 
	 * @param args
	 *            arguments
	 */
	public static void main(String[] args) throws Exception {
		start(args);
	}

}
