/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003, University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import edu.umd.cs.pugh.io.IO;
import edu.umd.cs.pugh.visitclass.Constants2;
import org.apache.bcel.classfile.*;
import org.apache.bcel.Repository;

/**
 * An instance of this class is used to apply the selected set of
 * analyses on some collection of Java classes.  It also implements the
 * comand line interface.
 *
 * @author Bill Pugh
 * @author David Hovemeyer
 */
public class FindBugs implements Constants2
{
  /* ----------------------------------------------------------------------
   * Helper classes
   * ---------------------------------------------------------------------- */

  /**
   * Interface for an object representing a source of class files to analyze.
   */
  private interface ClassProducer {
	/**
	 * Get the next class to analyze.
	 * @return the class, or null of there are no more classes for this ClassProducer
	 * @throws IOException if an IOException occurs
	 * @throws InterruptedException if the thread is interrupted
	 */
	public JavaClass getNextClass() throws IOException, InterruptedException;
  }

  /**
   * ClassProducer for single class files.
   */
  private static class SingleClassProducer implements ClassProducer {
	private String fileName;

	/**
	 * Constructor.
	 * @param fileName the single class file to be analyzed
	 */
	public SingleClassProducer(String fileName) {
		this.fileName = fileName;
	}

	public JavaClass getNextClass() throws IOException, InterruptedException {
		if (fileName == null)
			return null;
		if (Thread.interrupted())
			throw new InterruptedException();
		JavaClass jv = new ClassParser(fileName).parse();
		fileName = null; // don't return it next time
		return jv;
	}
  }

  /**
   * ClassProducer for .zip and .jar files.
   */
  private static class ZipClassProducer implements ClassProducer {
	private ZipFile zipFile;
	private Enumeration entries;

	/**
	 * Constructor.
	 * @param fileName the name of the zip or jar file
	 */
	public ZipClassProducer(String fileName) throws IOException {
		this.zipFile = new ZipFile(fileName);
		this.entries = zipFile.entries();
	}

	public JavaClass getNextClass() throws IOException, InterruptedException {
		ZipEntry classEntry = null;
		while (classEntry == null && entries.hasMoreElements()) {
			if (Thread.interrupted())
				throw new InterruptedException();
			ZipEntry entry = (ZipEntry) entries.nextElement();
			if (entry.getName().endsWith(".class"))
				classEntry = entry;
		}
		if (classEntry == null)
			return null;
		return new ClassParser(zipFile.getInputStream(classEntry), classEntry.getName()).parse();
	}
  }

  /**
   * ClassProducer for directories.
   * The directory is scanned recursively for class files.
   */
  private static class DirectoryClassProducer implements ClassProducer {
	private Iterator<String> rfsIter;

	public DirectoryClassProducer(String dirName) throws InterruptedException {
		FileFilter filter = new FileFilter() {
			public boolean accept(File file) {
				return file.isDirectory() || file.getName().endsWith(".class");
			}
		};

		// This will throw InterruptedException if the thread is
		// interrupted.
		RecursiveFileSearch rfs = new RecursiveFileSearch(dirName, filter).search();
		this.rfsIter = rfs.fileNameIterator();
	}

	public JavaClass getNextClass() throws IOException, InterruptedException {
		if (!rfsIter.hasNext())
			return null;
		String fileName = rfsIter.next();
		return new ClassParser(fileName).parse();
	}
  }

  /* ----------------------------------------------------------------------
   * Member variables
   * ---------------------------------------------------------------------- */

  private static final boolean DEBUG = Boolean.getBoolean("findbugs.debug");

  private BugReporter bugReporter;
  private Detector detectors [];
  private FindBugsProgress progressCallback;

  /* ----------------------------------------------------------------------
   * Public methods
   * ---------------------------------------------------------------------- */

  /**
   * Constructor.
   * @param bugReporter the BugReporter object that will be used to report
   *   BugInstance objects, analysis errors, class to source mapping, etc.
   */
  public FindBugs(BugReporter bugReporter) {
	if (bugReporter == null)
		throw new IllegalArgumentException("null bugReporter");
	this.bugReporter = bugReporter;

	// Create a no-op progress callback.
	this.progressCallback = new FindBugsProgress() {
		public void reportNumberOfArchives(int numArchives) { }
		public void finishArchive() { }
		public void startAnalysis(int numClasses) { }
		public void finishClass() { }
		public void finishPerClassAnalysis() { }
	};
  }

  /**
   * Set the progress callback that will be used to keep track
   * of the progress of the analysis.
   * @param progressCallback the progress callback
   */
  public void setProgressCallback(FindBugsProgress progressCallback) {
	this.progressCallback = progressCallback;
  }

  /**
   * Set filter of bug instances to include or exclude.
   * @param filterFileName the name of the filter file
   * @param include true if the filter specifies bug instances to include,
   *   false if it specifies bug instances to exclude
   */
  public void setFilter(String filterFileName, boolean include) throws IOException, FilterException {
	Filter filter = new Filter(filterFileName);
	bugReporter = new FilterBugReporter(bugReporter, filter, include);
  }

  /**
   * Execute FindBugs on given list of files (which may be jar files or class files).
   * All bugs found are reported to the BugReporter object which was set
   * when this object was constructed.
   * @param argv list of files to analyze
   * @throws java.io.IOException if an I/O exception occurs analyzing one of the files
   * @throws InterruptedException if the thread is interrupted while conducting the analysis
   */
  public void execute(String[] argv) throws java.io.IOException, InterruptedException {
	if (detectors == null)
		createDetectors();

	// Purge repository of previous contents
	Repository.clearCache();

	progressCallback.reportNumberOfArchives(argv.length);

	List<String> repositoryClassList = new LinkedList<String>();

	for (int i = 0; i < argv.length; i++) {
		addFileToRepository(argv[i], repositoryClassList);
		}

	progressCallback.startAnalysis(repositoryClassList.size());

	for (Iterator<String> i = repositoryClassList.iterator(); i.hasNext(); ) {
		String className = i.next();
		examineClass(className);
		}

	progressCallback.finishPerClassAnalysis();

	this.reportFinal();

	// Flush any queued bug reports
	bugReporter.finish();

	// Flush any queued error reports
	bugReporter.reportQueuedErrors();
  }

  /**
   * Get the source file in which the given class is defined.
   * Assumes that execute() has already been called.
   * @param className fully qualified class name
   * @return name of the source file in which the class is defined
   */
  public String getSourceFile(String className) {
	return bugReporter.getSourceForClass(className);
  }

  /* ----------------------------------------------------------------------
   * Private methods
   * ---------------------------------------------------------------------- */

  /**
   * Create Detectors for each DetectorFactory which is enabled.
   * This will populate the detectors array.
   */
  private void createDetectors() {
	ArrayList<Detector> result = new ArrayList<Detector>();

	Iterator<DetectorFactory> i = DetectorFactoryCollection.instance().factoryIterator();
	int count = 0;
	while (i.hasNext()) {
		DetectorFactory factory = i.next();
		if (factory.isEnabled())
			result.add(factory.create(bugReporter));
	}

	detectors = result.toArray(new Detector[0]);
  }

  /**
   * Add all classes contained in given file to the BCEL Repository.
   * @param fileName the file, which may be a jar/zip archive, a single class file,
   *   or a directory to be recursively searched for class files
   */
  private void addFileToRepository(String fileName, List<String> repositoryClassList)
	throws IOException, InterruptedException {

     try {
	ClassProducer classProducer;

	// Create the ClassProducer
	if (fileName.endsWith(".jar") || fileName.endsWith(".zip"))
		classProducer = new ZipClassProducer(fileName);
	else if (fileName.endsWith(".class"))
		classProducer = new SingleClassProducer(fileName);
	else {
		File dir = new File(fileName);
		if (!dir.isDirectory())
			throw new IOException("Path " + fileName + " is not an archive, class file, or directory");
		classProducer = new DirectoryClassProducer(fileName);
	}

	// Load all referenced classes into the Repository
	for (;;) {
		if (Thread.interrupted())
			throw new InterruptedException();
		JavaClass jclass = classProducer.getNextClass();
		if (jclass == null)
			break;
		Repository.addClass(jclass);
		repositoryClassList.add(jclass.getClassName());
	}

	progressCallback.finishArchive();

     } catch (IOException e) {
	// You'd think that the message for a FileNotFoundException would include
	// the filename, but you'd be wrong.  So, we'll add it explicitly.
	throw new IOException("Could not analyze " + fileName + ": " + e.getMessage());
     }
  }

  /**
   * Examine a single class by invoking all of the Detectors on it.
   * @param className the fully qualified name of the class to examine
   */
  private void examineClass(String className) throws InterruptedException {
	if (DEBUG) System.out.println("Examining class " + className);

	JavaClass javaClass;
	try {
		javaClass = Repository.lookupClass(className);
	} catch (ClassNotFoundException e) {
		throw new AnalysisException("Could not find class " + className + " in Repository", e);
	}

	bugReporter.mapClassToSource(javaClass.getClassName(), javaClass.getSourceFileName());
	ClassContext classContext = new ClassContext(javaClass);

	for (int i = 0; i < detectors.length; ++i) {
		if (Thread.interrupted())
			throw new InterruptedException();
		try {
			Detector detector = detectors[i];
			if (DEBUG) System.out.println("  running " + detector.getClass().getName());
			try {
				detector.visitClassContext(classContext);
			} catch (AnalysisException e) {
				bugReporter.logError(e.toString());
			}
		} catch (AnalysisException e) {
			bugReporter.logError("Analysis exception: " + e.toString());
		}
	}

	progressCallback.finishClass();
  }

  /**
   * Call report() on all detectors, to give them a chance to
   * report any accumulated bug reports.
   */
  private void reportFinal() throws InterruptedException {
	for (int i = 0; i < detectors.length; ++i) {
		if (Thread.interrupted())
			throw new InterruptedException();
		detectors[i].report();
	}
  }

  /* ----------------------------------------------------------------------
   * main() method
   * ---------------------------------------------------------------------- */

  public static int lowestPriorityReported = Detector.NORMAL_PRIORITY;
  public static void main(String argv[]) throws Exception
  { 
	BugReporter bugReporter = null;
	boolean quiet = false;
	String filterFile = null;
	boolean include = false;

	// Process command line options
	int argCount = 0;
	while (argCount < argv.length) {
		String option = argv[argCount];
		if (!option.startsWith("-"))
			break;
		if (option.equals("-low"))
			lowestPriorityReported = Detector.LOW_PRIORITY;
		else if (option.equals("-medium"))
			lowestPriorityReported = Detector.NORMAL_PRIORITY;
		else if (option.equals("-high"))
			lowestPriorityReported = Detector.HIGH_PRIORITY;
		else if (option.equals("-sortByClass"))
			bugReporter = new SortingBugReporter();
		else if (option.equals("-xml"))
			bugReporter = new XMLBugReporter();
		else if (option.equals("-visitors") || option.equals("-omitVisitors")) {
			++argCount;
			if (argCount == argv.length) throw new IllegalArgumentException(option + " option requires argument");
			boolean omit = option.equals("-omitVisitors");

			if (!omit) {
				// Selecting detectors explicitly, so start out by
				// disabling all of them.  The selected ones will
				// be re-enabled.
				Iterator<DetectorFactory> factoryIter =
					DetectorFactoryCollection.instance().factoryIterator();
				while (factoryIter.hasNext()) {
					DetectorFactory factory = factoryIter.next();
					factory.setEnabled(false);
				}
			}

			// Explicitly enable or disable the selector detectors.
			StringTokenizer tok = new StringTokenizer(argv[argCount], ",");
			while (tok.hasMoreTokens()) {
				String visitorName = tok.nextToken();
				DetectorFactory factory = DetectorFactoryCollection.instance().getFactory(visitorName);
				if (factory == null)
					throw new IllegalArgumentException("Unknown detector: " + visitorName);
				factory.setEnabled(!omit);
			}
		} else if (option.equals("-exclude") || option.equals("-include")) {
			++argCount;
			if (argCount == argv.length) throw new IllegalArgumentException(option + " option requires argument");
			filterFile = argv[argCount];
			include = option.equals("-include");
		} else if (option.equals("-quiet")) {
			quiet = true;
		} else
			throw new IllegalArgumentException("Unknown option: " + option);
		++argCount;
	}

	if (argCount == argv.length) {
		InputStream in = FindBugs.class.getClassLoader().getResourceAsStream("USAGE");
		if (in == null)  {
			System.out.println("FindBugs tool, version " + Version.RELEASE);
			System.out.println("usage: java -jar findbugs.jar [options] <classfiles, zip files or jar files>");
			System.out.println("Example: java -jar findbugs.jar rt.jar");
			System.out.println("Options:");
			System.out.println("   -quiet                                 suppress error messages");
			System.out.println("   -sortByClass                           sort bug reports by class");
			System.out.println("   -xml                                   XML output");
			System.out.println("   -visitors <visitor 1>,<visitor 2>,...  run only named visitors");
			System.out.println("   -omitVisitors <v1>,<v2>,...            omit named visitors");
			System.out.println("   -exclude <filter file>                 exclude bugs matching given filter");
			System.out.println("   -include <filter file>                 include only bugs matching given filter");
			}
		else
			IO.copy(in,System.out);
		return;
		}

	if (bugReporter == null)
		bugReporter = new PrintingBugReporter();

	if (quiet)
		bugReporter.setErrorVerbosity(BugReporter.SILENT);

	FindBugs findBugs = new FindBugs(bugReporter);

	if (filterFile != null)
		findBugs.setFilter(filterFile, include);

	String[] fileList = new String[argv.length - argCount];
	System.arraycopy(argv, argCount, fileList, 0, fileList.length);

	findBugs.execute(fileList);

  }
}
