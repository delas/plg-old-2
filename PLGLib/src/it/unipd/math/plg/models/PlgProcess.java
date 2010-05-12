package it.unipd.math.plg.models;

import it.unipd.math.plg.metrics.PlgMetricCalculator;
import it.unipd.math.plg.metrics.PlgProcessMeasures;
import it.unipd.math.plg.models.PlgActivity.RELATIONS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.processmining.converting.HNetToPetriNetConverter;
import org.processmining.framework.models.heuristics.HNSubSet;
import org.processmining.framework.models.heuristics.HeuristicsNet;
import org.processmining.framework.models.petrinet.PetriNet;
import org.processmining.framework.plugin.ProvidedObject;
import org.processmining.importing.heuristicsnet.HeuristicsNetFromFile;
import org.processmining.lib.mxml.AuditTrailEntry;
import org.processmining.lib.mxml.LogException;
import org.processmining.lib.mxml.writing.Process;
import org.processmining.lib.mxml.writing.ProcessInstance;
import org.processmining.lib.mxml.writing.ProcessInstanceType;
import org.processmining.lib.mxml.writing.impl.LogSetRandomImpl;
import org.processmining.lib.mxml.writing.persistency.LogPersistencyZip;
import org.processmining.lib.xml.Document;
import org.processmining.lib.xml.Tag;
import org.processmining.mining.petrinetmining.PetriNetResult;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * This class describres a general process.
 * 
 * Actually a process contains its name and its first activity. With this
 * modelling, since each activity contains a set of related activities, we have
 * all the process described as a "linked list" where each node is an activity
 * and the edge is typed (describing the relation as sequence, AND split/join 
 * and XOR split/join).
 * 
 * @author Andrea Burattin
 * @version 0.4
 */
public class PlgProcess {

	/** This is the random number generator */
	public static Random generator = new Random();
	/** This is the current library version */
	public static final String version = "0.4alpha";
	
	/**
	 * This enum describes the possible stats counter for the pattern an other
	 * process entities
	 */
	public static enum COUNTER_TYPES {
		/** This indicates the number of loops */
		LOOP,
		/** This indicates the number of single activities */
		ALONE,
		/** This indicates the number of sequence of activities */
		SEQUENCE,
		/** This indicates the number of AND splits */
		AND,
		/** This indicates the number of XOR splits */
		XOR,
		/** This indicates the maximum number of AND branches */
		MAX_AND_BRANCHES,
		/** This indicates the maximum number of XOR branches */
		MAX_XOR_BRANCHES,
		/** This indicates the number of empty patter */
		EMPTY
	};
	
	private String name;
	private PlgActivity firstActivity = null;
	private PlgActivity lastActivity = null;
	private Vector<PlgActivity> activityList = null;
//	private int activityGenerator = 'A' - 1;
	private String activityGenerator = "";
	private HeuristicsNet heuristicsNet = null;
	private PetriNet petriNet = null;
	private int maxDepth = 0;
	private HashMap<COUNTER_TYPES, Integer> statsCounter;
	private PlgProcessMeasures metrics = null;
	
	private PlgParameters parameters = null;
	
	
	/**
	 * Default class constructor
	 * 
	 * @param name the new process name
	 * @param starting the first activity of the process
	 */
	public PlgProcess(String name, PlgActivity starting) {
		setName(name);
		this.firstActivity = starting;
		this.activityList = new Vector<PlgActivity>();
		this.statsCounter = new HashMap<COUNTER_TYPES, Integer>();
	}
	
	
	/**
	 * Default class constructor
	 * 
	 * @param name the new process name
	 */
	public PlgProcess(String name) {
		this(name, null);
	}

	
	/**
	 * Sets the process name
	 * 
	 * @param name the new process name
	 */
	public void setName(String name) {
		this.name = name;
	}

	
	/**
	 * Gets the process name
	 * 
	 * @return the process name
	 */
	public String getName() {
		return name;
	}
	
	
	/**
	 * Gets the first process activity
	 * 
	 * @return the first activity
	 */
	public PlgActivity getFirstActivity() {
		if (firstActivity == null) {
			for (PlgActivity activity : activityList) {
				if (activity.getRelationsFrom().isEmpty()) {
					firstActivity = activity;
				}
			}
		}
		return firstActivity;
	}


	/**
	 * Sets the first process activity
	 * 
	 * @param firstActivity the new first process activity
	 */
	public void setFirstActivity(PlgActivity firstActivity) {
		this.firstActivity = firstActivity;
	}
	
	
	/**
	 * Gets the last process activity
	 * 
	 * @return the last activity
	 */
	public PlgActivity getLastActivity() {
		if (lastActivity == null) {
			for (PlgActivity activity : activityList) {
				if (activity.getRelationsTo().isEmpty()) {
					lastActivity = activity;
				}
			}
		}
		return lastActivity;
	}


	/**
	 * Sets the last process activity
	 * 
	 * @param lastActivity the new last process activity
	 */
	public void setLastActivity(PlgActivity lastActivity) {
		this.lastActivity = lastActivity;
	}
	
	
	/**
	 * Gets the activity list
	 * 
	 * @return the activity list
	 */
	public Vector<PlgActivity> getActivityList() {
		return activityList;
	}
	
	
	/**
	 * This method asks for a new activity name
	 * 
	 * @param previous
	 * @return
	 */
	private String askNewName(String previous) {
		if (previous.isEmpty()) {
			return "A";
		}
		char c = previous.charAt(previous.length()-1);
		if (c != 'Z') {
			return previous.substring(0, previous.length()-1) + String.valueOf((char)(c + 1));
		} else {
			return askNewName(previous.substring(0, previous.length()-1)) + "A";
		}
	}
	
	
	/**
	 * Asks the process for a new random activity. If the process has not a
	 * starting activity, the first call to this method will consider the new
	 * activity as the start of the process.
	 *
	 * @return a new activity with a random name
	 */
	public PlgActivity askNewActivity() {
		activityGenerator = askNewName(activityGenerator);		
		return new PlgActivity(this, activityGenerator);
	}

	
	/**
	 * Saves the current Petri Net process model as a Dot file
	 * 
	 * @param filename the destination filename
	 * @throws IOException
	 */
	public void savePetriNetAsDot(String filename) throws IOException {
		FileWriter fw = new FileWriter(filename);
		getPetriNet().writeToDot(fw);
		fw.close();
	}

	
	/**
	 * Saves the current Petri Net process model as a Dot file
	 * 
	 * @param filename the destination filename
	 * @throws IOException
	 */
	public void saveHeuristicsNetAsDot(String filename) throws IOException {
		FileWriter fw = new FileWriter(filename);
		HeuristicsNet hn = getHeuristicsNet();
		hn.writeToDot(fw);
		fw.close();
	}
	
	
	/**
	 * Generates and saves a new instance of the process. This method saves the
	 * log in a zip file format. This method can generate activitities as time
	 * interval or as time points (in this case)
	 * 
	 * @param filename the destination zip file (must finishes with .zip)
	 * @param cases the number of cases to generate
	 * @param percentAsInterval the percentual of activities as time interval
	 * @param percentErrors the percentage of log traces with errors
	 * @throws IOException
	 * @throws LogException
	 */
	public void saveAsNewLog(String filename, int cases, int percentAsInterval, int percentErrors) throws IOException, LogException {
		cases = (cases < 1)? 1 : cases;
		percentAsInterval = (percentAsInterval < 0 || percentAsInterval > 100)? 100 : percentAsInterval;
		percentErrors = (percentErrors < 0 || percentErrors > 100)? 0 : percentErrors;
		
		File file = new File(filename);
		LogPersistencyZip logFilter = new LogPersistencyZip(file);
		LogSetRandomImpl logSet = new LogSetRandomImpl(logFilter, "ProcessLogGenerator", "", 10);
		
		while (cases-- > 0) {
			Vector<PlgObservation> v = getFirstActivity().generateInstance(0);
			for (int i = 0; i < v.size(); i ++) {
				if (PlgParameters.randomFromPercent(percentErrors)) {
					/* There must be an error! In this context, an error is the
					 * swap between two activities' times or the deletion of a
					 * log trace
					 */
//					if (generator.nextBoolean()) {
						// swap time
						PlgObservation o1 = v.get(i);
						int randomIndex;
						do {
							randomIndex = generator.nextInt(v.size());
						} while (randomIndex == i);
						PlgObservation o2 = v.get(randomIndex);
						// swap starting time
						int o1StartingTime = o1.getStartingTime();
						o1.setStartingTime(o2.getStartingTime());
						o2.setStartingTime(o1StartingTime);
//					} else {
						// delete action, just mark this, and delete them when
						// the whole loop is completed!
//						v.set(i, null);
//					}
				}
				PlgObservation o = v.get(i);
//				if (o != null) {
					String processName = o.getActivity().getProcess().getName();
					String caseId = "instance_" + cases;
					boolean asInterval = generator.nextInt(101) <= percentAsInterval;
					Process proc = logSet.getProcess(processName);
					ProcessInstance pi = proc.getProcessInstance(caseId, ProcessInstanceType.ENACTMENT_LOG);
					AuditTrailEntry[] ate = o.getAuditTrailEntry(asInterval);
					pi.addAuditTrailEntry(ate[0]);
					pi.addAuditTrailEntry(ate[1]);
//				}
			}
		}
		
		logSet.finish();
	}
	
	
	/**
	 * Generates and saves a new instance of the process. This method saves the
	 * log in a zip file format. This method can generate activitities as time
	 * interval or as time points (in this case)
	 * 
	 * @param filename the destination zip file (must finishes with .zip)
	 * @param cases the number of cases to generate
	 * @param percentAsInterval the percentual of activities as time interval
	 * @throws IOException
	 * @throws LogException
	 */
	public void saveAsNewLog(String filename, int cases, int percentAsInterval) throws IOException, LogException {
		saveAsNewLog(filename, cases, percentAsInterval, 0);
	}
	
	
	/**
	 * Generates and saves a new instance of the process. This method saves the
	 * log in a zip file format. This method can generate activitities as time
	 * interval or as time points (in this case)
	 * 
	 * @param filename the destination zip file (must finishes with .zip)
	 * @param cases the number of cases to generate
	 * @throws IOException
	 * @throws LogException
	 */
	public void saveAsNewLog(String filename, int cases) throws IOException, LogException {
		saveAsNewLog(filename, cases, 100, 0);
	}
	
	
	/**
	 * This method cleans the cache for the process models (both the Heuristics
	 * Net and the Petri Net). Usually there is no need to call this method,
	 * since the cache refresh is automatic.
	 */
	public void cleanModelCache() {
		firstActivity = null;
		lastActivity = null;
		// refresh heuristics net
		heuristicsNet = null;
		petriNet = null;
		metrics = null;
	}
	
	
	/**
	 * This method returns the Heuristics Net associated to the process
	 * 
	 * @return the Heuristics Net associated to the process
	 */
	public HeuristicsNet getHeuristicsNet() {
		// check the object in the cache
		if (heuristicsNet != null) {
			return heuristicsNet;
		}
		
		try {
//			File tempFile = File.createTempFile("temporary-heuristics", ".dot");
//			tempFile.deleteOnExit();
			File tempFile = new File("/home/delas/doc/workspace/PLGLib/test/hn.hn");
			FileWriter os = new FileWriter(tempFile);

			String separator = "/////////////////////\n";
			String file = "";
			String activityListString = "";
			String firstActivityString = "";
			String lastActivityString = "";

			// start and finish activities, and activity list
			for (int i = 0; i < activityList.size(); i++) {
				PlgActivity current = activityList.get(i);
				activityListString += current.getName() + ":@" + i + "&\n";
				if (current.equals(getFirstActivity())) {
					firstActivityString += i + "@\n";
				}
				if (current.equals(getLastActivity())) {
					lastActivityString += i + "@\n";
				}
			}
			file = 
				separator + 
				firstActivityString + 
				separator + 
				lastActivityString +
				separator + 
				activityListString +
				separator;

			os.write(file);
			
			for (int i = 0; i < activityList.size(); i++) {
				PlgActivity current = activityList.get(i);
				String toRet = current.getActivityId();
				// activity origin
				toRet += "@";
				if (getFirstActivity().equals(current)) {
					toRet += ".";
				} else {
					int tot = current.getRelationsFrom().size();
					int curr = 1;
					for (Iterator<PlgActivity> j = current.getRelationsFrom().iterator(); j.hasNext();) {
						toRet += j.next().getActivityId();
						if (current.isXorJoin() && curr < tot) {
							toRet += "|";
						} else if (current.isAndJoin() && curr < tot) {
							toRet += "&";
						}
						curr++;
					}
				}
				
				// activity destination
				toRet += "@";
				int tot = current.getRelationsTo().size();
				int curr = 1;
				for (Iterator<PlgActivity> j = current.getRelationsTo().iterator(); j.hasNext();) {
					toRet += j.next().getActivityId();
					if (current.getRelationType().equals(RELATIONS.XOR_SPLIT) && curr < tot) {
						toRet += "|";
					} else if (current.getRelationType().equals(RELATIONS.AND_SPLIT) && curr < tot) {
						toRet += "&";
					}
					curr++;
				}
				
				toRet += "\n";
				os.write(toRet);
			}

//			firstActivity.getHeuristicsNetFile(os, new Stack<PlgActivity>());
			os.close();

			HeuristicsNetFromFile hnff = new HeuristicsNetFromFile(new FileInputStream(tempFile));
			heuristicsNet = hnff.getNet();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		return heuristicsNet;
	}
	
	
	/**
	 * This method returns the Petri Net associated to the process 
	 * 
	 * @return the Petri Net associated to the process
	 * @throws IOException
	 */
	public PetriNet getPetriNet() throws IOException {
		// check the object in the cache
		if (petriNet != null) {
			return petriNet;
		}
		
		ProvidedObject po = new ProvidedObject("net", getHeuristicsNet());
		HNetToPetriNetConverter converter = new HNetToPetriNetConverter();
		petriNet = ((PetriNetResult) converter.convert(po)).getPetriNet();
		return petriNet;
	}
	
	
	/**
	 * This method to convert a Heuristics Net into an adjacency matrix
	 * 
	 * @see PlgProcess#getActivityList()
	 * @param hn the Heuristics Net to convert
	 * @return a boolean matrix where, the cell (i,j) contains true if there is
	 * a connection from activity i to j
	 */
	public static boolean[][] heuristicsNetToAdjacencyMatrix(HeuristicsNet hn) {
		int activityCounter = hn.size();
		boolean[][] adjacencyMatrix = new boolean[activityCounter][activityCounter];
		for (int i = 0; i < activityCounter; i++) {
			HNSubSet subset = hn.getAllElementsOutputSet(i);
			for (int j = 0; j < subset.size(); j++) {
				adjacencyMatrix[i][subset.get(j)] = true;
			}
		}
		return adjacencyMatrix;
	}
	
	
	/**
	 * This method returns the adjacency matrix for the current process
	 * 
	 * @see PlgProcess#heuristicsNetToAdjacencyMatrix(HeuristicsNet)
	 * @return a boolean matrix where, the cell (i,j) contains true if there is
	 * a connection from activity i to j
	 */
	public boolean[][] getAdjacencyMatrix() {
		return heuristicsNetToAdjacencyMatrix(getHeuristicsNet());
	}
	
	
	/**
	 * This method returns the number of instances per activity pattern
	 * 
	 * @return a hash map with the association pattern > counter
	 */
	public HashMap<COUNTER_TYPES, Integer> getPatternsCounter() {
		return statsCounter;
	}
	
	
	/**
	 * This method returns the number of instances of a particular pattern or,
	 * more generally some statistics about the process
	 * 
	 * @param pattern the name of the pattern
	 * @return the number of instances of a particular pattern
	 */
	public Integer getPatternsCounter(COUNTER_TYPES pattern) {
		Integer i = statsCounter.get(pattern);
		if (i == null) {
			return 0;
		}
		return i;
	}
	
	
	/**
	 * This method returns the maximum network depth
	 * 
	 * @return the maximum network depth
	 */
	public int getMaxDepth() {
		return maxDepth;
	}
	
	
	@Override
	public int hashCode() {
		return getHeuristicsNet().hashCode();
	}
	
	
	/**
	 * This method sets the number of times a pattern is used or, more
	 * generally some statistics about the process
	 * 
	 * @param pattern the name of the pattern to increment
	 * @param counter the new value for the counter
	 */
	private void setPatternCounter(COUNTER_TYPES pattern, Integer counter) {
		statsCounter.put(pattern, counter);
	}
	
	
	/**
	 * This method increments the number of times a pattern is used or, more
	 * generally some statistics about the process
	 * 
	 * @param pattern the name of the pattern to increment
	 */
	private void incrementPatternCounter(COUNTER_TYPES pattern) {
		Integer counter = statsCounter.get(pattern);
		if (counter == null) {
			counter = new Integer(0);
		}
		counter++;
		statsCounter.put(pattern, counter);
	}
	
	
	/**
	 * This method decrements the number of times a pattern is used or, more
	 * generally some statistics about the process
	 * 
	 * @param pattern the name of the pattern to decrement
	 */
	@SuppressWarnings("unused")
	private void decrementPatternCounter(COUNTER_TYPES pattern) {
		Integer counter = statsCounter.get(pattern);
		if (counter == null || counter == 0) {
			counter = new Integer(0);
		} else {
			counter--;
			statsCounter.put(pattern, counter);
		}
	}
	
	
	/**
	 * This method is used to scan the whole set of activities in order to find
	 * the required one.
	 * 
	 * @param activityId the identifier of the activity
	 * @return the required activity or <tt>null</tt>, if the activity is not
	 * found
	 */
	public PlgActivity searchActivityFromId(String activityId) {
		for (PlgActivity a : activityList) {
			if (a.getActivityId().equals(activityId)) {
				return a;
			}
		}
		return null;
	}
	
	
	/**
	 * This method is used to scan the whole set of activities in order to find
	 * the required one.
	 * 
	 * @param activityName the name of the activity
	 * @return the required activity or <tt>null</tt>, if the activity is not
	 * found
	 */
	public PlgActivity searchActivityFromName(String activityName) {
		for (PlgActivity a : activityList) {
			if (a.getName().equals(activityName)) {
				return a;
			}
		}
		return null;
	}
	
	
	/**
	 * This method is used to export the current process into a single file.
	 * This exporting process only generates a ZIP file containing an XML file
	 * that describes the current process.
	 * 
	 * @param filename the destination filename
	 * @return true
	 * @throws IOException
	 */
	public boolean saveProcessAs(String filename) throws IOException {
		File tempFile = File.createTempFile("process", ".xml");
		tempFile.deleteOnExit();
		Document dom = new Document(tempFile);
		Tag process = dom.addNode("process");
		// Meta
		process.addComment("This is the list of all meta-attributes of the process");
		Tag meta = process.addChildNode("meta");
		meta.addChildNode("libVersion").addTextNode(version);
		meta.addChildNode("name").addTextNode(name);
		Tag tagFirstActivity = meta.addChildNode("firstActivity");
		if (firstActivity != null) {
			tagFirstActivity.addTextNode(getFirstActivity().getName());
		}
		Tag tagLastActivity = meta.addChildNode("lastActivity");
		if (firstActivity != null) {
			tagLastActivity.addTextNode(getLastActivity().getName());
		}
		meta.addChildNode("activityGenerator").addTextNode(activityGenerator);
		meta.addChildNode("maxDepth").addTextNode(new Integer(maxDepth).toString());
		Tag tagStatsCounter = meta.addChildNode("statsCounter");
		Iterator<COUNTER_TYPES> statsIterator = statsCounter.keySet().iterator();
		while (statsIterator.hasNext()) {
			COUNTER_TYPES type = statsIterator.next();
			Tag s = tagStatsCounter.addChildNode("stat");
			s.addAttribute("id", type.toString());
			s.addTextNode(statsCounter.get(type).toString());
		}
		// List of activities
		process.addComment("The following is the list of all activities");
		Tag activitiesList = process.addChildNode("activitiesList");
		for (PlgActivity activity : activityList) {
			Tag t = activitiesList.addChildNode("activity");
			t.addAttribute("name", activity.getName());
		}
		// List of relations
		process.addComment("The following list describes the relations between activities");
		Tag activities = process.addChildNode("activities");
		for (PlgActivity activity : activityList) {
			activity.getActivityAsXML(activities);
		}
		dom.close();
		// create the ZIP file
		ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(filename));
		FileInputStream in = new FileInputStream(tempFile.getAbsolutePath());
		out.putNextEntry(new ZipEntry("process.xml"));
        int len; byte[] buf = new byte[1024];
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        out.closeEntry();
        in.close();
        out.close();
		
		return true;
	}
	
	
	/**
	 * This method tries to build a process object starting from a correctly
	 * generated file. These file should be created with the
	 * <tt>saveProcessAs(String)</tt>.
	 * 
	 * @see #saveProcessAs(String)
	 * @param filename the absolute path of the process file to load
	 * @return the built process, starting from the file; or null, if the file
	 * is not in the correct format 
	 * @throws IOException
	 */
	public static PlgProcess loadProcessFrom(String filename) throws IOException {
		try {
			// ZIP file extraction
			File tempFile = File.createTempFile("process", ".xml");
			tempFile.deleteOnExit();
			ZipInputStream zipinputstream = null;
			zipinputstream = new ZipInputStream(new FileInputStream(filename));
			zipinputstream.getNextEntry();
			int n; byte[] buf = new byte[1024];
			FileOutputStream fileoutputstream = new FileOutputStream(tempFile);
			while ((n = zipinputstream.read(buf, 0, 1024)) > -1) {
				fileoutputstream.write(buf, 0, n);
			}
			fileoutputstream.close();
			zipinputstream.closeEntry();
			zipinputstream.close();
			
			// XML extraction
			FileInputStream fi = new FileInputStream(tempFile);
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			org.w3c.dom.Document doc;
			dbf.setValidating(false);
			dbf.setIgnoringComments(true);
			dbf.setIgnoringElementContentWhitespace(true);
			doc = dbf.newDocumentBuilder().parse(fi);
			NodeList nodes; Node node;
			
			PlgProcess p = new PlgProcess("");
			// List of activities
			node = doc.getElementsByTagName("activitiesList").item(0);
			nodes = node.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				if (nodes.item(i).getNodeName().equals("activity")) {
					String actName = nodes.item(i)
						.getAttributes()
						.getNamedItem("name")
						.getTextContent();
					new PlgActivity(p, actName);
				}
			}
			// Meta
			node = doc.getElementsByTagName("meta").item(0);
			nodes = node.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				String nodeName = nodes.item(i).getNodeName();
				String nodeValue = nodes.item(i).getTextContent();
				if (nodeName.equals("name")) {
					p.setName(nodeValue);
				} else if (nodeName.equals("firstActivity")) {
					p.firstActivity = p.searchActivityFromName(nodeValue);
				} else if (nodeName.equals("lastActivity")) {
					p.lastActivity = p.searchActivityFromName(nodeValue);
				} else if (nodeName.equals("activityGenerator")) {
					p.activityGenerator = nodeValue;
				} else if (nodeName.equals("maxDepth")) {
					p.maxDepth = new Integer(nodeValue).intValue();
				}
			}
			node = doc.getElementsByTagName("statsCounter").item(0);
			nodes = node.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				String nodeName = nodes.item(i).getNodeName();
				if (nodeName.equals("stat")) {
					String nodeValue = nodes.item(i).getTextContent();
					Integer nodeIntValue = new Integer(nodeValue);
					String idValue = nodes.item(i).getAttributes().getNamedItem("id").getTextContent();
					p.statsCounter.put(COUNTER_TYPES.valueOf(idValue), nodeIntValue);
				}
			}
			// List of relations
			node = doc.getElementsByTagName("activities").item(0);
			nodes = node.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				String nodeName = nodes.item(i).getNodeName();
				if (nodeName.equals("activity")) {
					String actName = nodes.item(i)
						.getAttributes()
						.getNamedItem("id")
						.getTextContent();
					p.searchActivityFromName(actName).setActivityFromXML(nodes.item(i));
				}
			}
				        
			return p;
			
		} catch (SAXException e) {
			e.printStackTrace();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	
	/* ********************************************************************** */
	/*                             Randomization                              */
	/* ********************************************************************** */
	/**
	 * This method populates the current process with some random activities.
	 * This is the "simple version" of this method, equals probability to each
	 * parameters and AND and XOR maximum branches to 3.
	 * 
	 * @param deep the maximum network deep
	 */
	public void randomize(int deep) {
		// pack parameters
		PlgParameters parameters = new PlgParameters(
				4,  // max and branches
				4,  // max xor branches
				80, // loop prob
				20, // single act prob
				35, // sequence act prob
				25, // and prob
				25, // xor prob
				25, // empty activity prob
				deep // deep
		);
		randomize(parameters);
	}
	
	
	/**
	 * This method populates the current process with some random activities
	 * 
	 * @param ANDBranches the maximum number of AND branches (must be > 1)
	 * @param XORBranches the maximum number of XOR branches (must be > 1)
	 * @param loopPercent the loop probability (must be >= 0 and <= 100)
	 * @param singleActivityProbability the probability of single activity (must 
	 * be >= 0 and <= 100)
	 * @param sequenceProbability the probability of sequence activity (must be 
	 * >= 0 and <= 100)
	 * @param ANDProbability the probability of AND split-join (must be >= 0 and 
	 * <= 100)
	 * @param XORProbability the probability of XOR split-join (must be >= 0 and 
	 * <= 100)
	 * @param emptyProbability the probability of an empty pattern (must be >= 0
	 * and <= 100)
	 * @param deep the maximum network deep
	 */
	public void randomize(int ANDBranches, int XORBranches, int loopPercent,
			int singleActivityProbability, int sequenceProbability,
			int ANDProbability, int XORProbability, int emptyProbability, int deep) {
		// pack parameters
		PlgParameters parameters = new PlgParameters(ANDBranches, XORBranches,
				loopPercent, singleActivityProbability, sequenceProbability,
				ANDProbability, XORProbability, emptyProbability, deep);
		randomize(parameters);
	}
	
	
	/**
	 * This method populates the current process with some random activities
	 * 
	 * @param parameters the object with all the parameters
	 */
	public void randomize(PlgParameters parameters) {
		this.parameters = parameters;
		int maxNested = parameters.getDeep();
		
		PlgActivity start = askNewActivity();
		PlgActivity end = askNewActivity();
		
		PlgPatternFrame body = new PlgPatternFrame(end, start);
//		getPatternAnd(body, 3);
		askInternalPattern(body, maxNested);
//		askInternalPattern(body, 3);
	}
		
	private PlgPatternFrame askInternalPattern(PlgPatternFrame container, int maxNested) {
		
		if (maxNested < 1) {
			return getPatternActivity(container, maxNested - 1);
		}
		
		PlgPatternFrame pattern = null;
		PlgParameters.PATTERN nextAction = parameters.getRandomActionInSeqAndXor();
		
		if (nextAction.equals(PlgParameters.PATTERN.SEQUENCE)) {
			pattern = getPatternSequence(container, maxNested - 1);
		} else if (nextAction.equals(PlgParameters.PATTERN.AND)) {
			pattern = getPatternAnd(container, maxNested - 1);
		} else if (nextAction.equals(PlgParameters.PATTERN.XOR)) {
			pattern = getPatternXor(container, maxNested - 1);
		} else {
			pattern = getPatternActivity(container, maxNested - 1);
		}
		
		// loop stuff
//		if (parameters.getLoopPresence() && !pattern.getHead().equals(pattern.getTail())) {
//			getPatternLoop(pattern, maxNested - 1);
//		}
		
		return pattern;
	}
	
	private PlgPatternFrame getPatternActivity(PlgPatternFrame container, int maxNested) {
		// get the single activity
		PlgActivity a = askNewActivity();
		// connect it to the container pattern
		container.getTail().addNext(a);
		a.addNext(container.getHead());
		return new PlgPatternFrame(a, a);
	}
	
	private PlgPatternFrame getPatternSequence(PlgPatternFrame container, int maxNested) {
		// get the first subgraph
		PlgPatternFrame fst = askInternalPattern(container, maxNested - 1);
		fst.getHead().removeConnection(container.getHead());
		// get the second subgraph
		PlgPatternFrame sndBody = new PlgPatternFrame(container.getHead(), fst.getHead());
		PlgPatternFrame snd = askInternalPattern(sndBody, maxNested - 1);
		
		return new PlgPatternFrame(snd.getHead(), fst.getTail());
	}
	
	private PlgPatternFrame getPatternSplitJoin(PlgPatternFrame container, PlgParameters.PATTERN type, int maxNested) {
		PlgPatternFrame split = askInternalPattern(container, maxNested);
		split.getHead().removeConnection(container.getHead());
		
		PlgPatternFrame joinBody = new PlgPatternFrame(container.getHead(), split.getHead());
		PlgPatternFrame join = askInternalPattern(joinBody, maxNested);
		
		container.getTail().addNext(split.getTail());
		join.getHead().addNext(container.getHead());
		split.getHead().removeConnection(join.getTail());
		
		int branches = 3;
	
		if (type == PlgParameters.PATTERN.AND) {
			branches = parameters.getRandomAndBranches();
			split.getHead().inAndUntil(join.getTail());
		} else {
			branches = parameters.getRandomXorBranches();
			split.getHead().inXorUntil(join.getTail());
		}
		
		PlgPatternFrame body = new PlgPatternFrame(join.getTail(), split.getHead());
		for(int i = 0; i < branches; i++) {
			askInternalPattern(body, maxNested - 1);
		}
		
		return new PlgPatternFrame(join.getHead(), split.getTail());
	}
	
	private PlgPatternFrame getPatternAnd(PlgPatternFrame container, int maxNested) {
		return getPatternSplitJoin(container, PlgParameters.PATTERN.AND, maxNested);
	}
	
	private PlgPatternFrame getPatternXor(PlgPatternFrame container, int maxNested) {
		return getPatternSplitJoin(container, PlgParameters.PATTERN.XOR, maxNested);
	}
	
	private void getPatternLoop(PlgPatternFrame bound, int maxNested) {
//		PlgActivity from = bound.getHead();
//		PlgActivity to = bound.getTail();
//		if (to.canBeLoopDestination() && from.canBeLoopDeparture()) {
//			PlgPatternFrame loopBody = askInternalPattern(maxNested);
//			from.inXorUntil(to);
//			from.addNext(loopBody.getTail());
//			loopBody.getTail().addNext(to);
//			System.out.println("loop ok");
//		} else
//			System.out.println("loop damn ["+ (!from.isAndJoin()) +"]");
	}
	
	
	
	
	
	/*private PlgPatternFrame getPatternXor(int maxNested) {		
		return null;
	}*/
	
	
//	/**
//	 * This method populates the current process with some random activities
//	 * 
//	 * @param parameters the object with all the parameters
//	 */
//	private void randomize(PlgParameters parameters) {
//		PlgParameters.PATTERN nextAction = parameters.getRandomActionInSeqAndXor();
//		
//		if (nextAction.equals(PlgParameters.PATTERN.SEQUENCE)) {
//			getPatternSequence(parameters, null, 0);
//		} else if (nextAction.equals(PlgParameters.PATTERN.AND)) {
//			getPatternAnd(parameters, null, 1);
//		} else {
//			getPatternXor(parameters, null, 1);
//		}
//	}
//
//	
//	/**
//	 * This method generates a random activity pattern
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @param maxNested
//	 * @return the head activity
//	 */
//	private PlgActivity askInternalPattern(PlgParameters parameters, PlgActivity to, int maxNested) {
//		return askInternalPattern(parameters, to, maxNested, true);
//	}
//
//	
//	/**
//	 * This method generates a random activity pattern
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @param maxNested
//	 * @param allowEmpty if the empty pattern is allowed
//	 * @return the head activity
//	 */
//	private PlgActivity askInternalPattern(PlgParameters parameters, PlgActivity to, int maxNested, boolean allowEmpty) {
//		int deep = parameters.getDeep() - 1;
//		parameters.setDeep(deep);
//		if (maxNested > maxDepth) {
//			maxDepth++;
//		}
//		if (deep == 0) {
//			return getPatternAlone(parameters, to, maxNested);
//		} else {
//			if (allowEmpty) {
//				PlgParameters.PATTERN nextAction = parameters.getRandomActionInAloneSeqAndXorEmpty();
//				
//				if (nextAction.equals(PlgParameters.PATTERN.SINGLE)) {
//					return getPatternAlone(parameters, to, maxNested);
//				} else if (nextAction.equals(PlgParameters.PATTERN.SEQUENCE)) {
//					return getPatternSequence(parameters, to, maxNested);
//				} else if (nextAction.equals(PlgParameters.PATTERN.AND)) {
//					return getPatternAnd(parameters, to, maxNested + 1);
//				} else if (nextAction.equals(PlgParameters.PATTERN.XOR)) {
//					return getPatternXor(parameters, to, maxNested + 1);
//				} else {
//					return getEmptyPatter(parameters, to, maxNested);
//				}
//			} else {
//				PlgParameters.PATTERN nextAction = parameters.getRandomActionInAloneSeqAndXor();
//				
//				if (nextAction.equals(PlgParameters.PATTERN.SINGLE)) {
//					return getPatternAlone(parameters, to, maxNested);
//				} else if (nextAction.equals(PlgParameters.PATTERN.SEQUENCE)) {
//					return getPatternSequence(parameters, to, maxNested);
//				} else if (nextAction.equals(PlgParameters.PATTERN.AND)) {
//					return getPatternAnd(parameters, to, maxNested + 1);
//				} else {
//					return getPatternXor(parameters, to, maxNested + 1);
//				}
//			}
//		}
//	}
//
//	
//	/**
//	 * This method generates a random activity, with this structure:
//	 * <pre>
//	 *   A
//	 * </pre>
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @return the head activity
//	 */
//	private PlgActivity getPatternAlone(PlgParameters parameters, PlgActivity to, int maxNested) {
//		PlgActivity a = askNewActivity();
//		// loop stuff
//		if (!a.equals(this.firstActivity)) {
//			if (PlgParameters.randomFromPercent(parameters.getLoopPercent())) {
//				incrementPatternCounter(COUNTER_TYPES.LOOP);
//				getLoopPattern(parameters, a, a, maxNested);
////				a.addLoop(a);
//			}
//		}
//		if (to != null) {
//			a.addNext(to);
//		}
//		
//		// counter update
//		incrementPatternCounter(COUNTER_TYPES.ALONE);
//		
//		return a;
//	}
//
//	
//	/**
//	 * This method generates random activities, with this structure:
//	 * <pre>
//	 *   o -> A -> ? -> B -> o
//	 * </pre>
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @return the head activity
//	 */
//	private PlgActivity getPatternSequence(PlgParameters parameters, PlgActivity to, int maxNested) {
//		PlgActivity a = askNewActivity();
//		PlgActivity b = askNewActivity();
//		a.addNext(askInternalPattern(parameters, b, maxNested));
//		
//		// loop stuff
//		if (!a.equals(this.firstActivity) && !b.equals(this.lastActivity)) {
//			if (PlgParameters.randomFromPercent(parameters.getLoopPercent())) {
//				incrementPatternCounter(COUNTER_TYPES.LOOP);
//				getLoopPattern(parameters, a, b, maxNested);
////				b.addLoop(a);
//			}
//		}
//		if (to != null) {
//			b.addNext(to);
//		}
//		
//		// counter update
//		incrementPatternCounter(COUNTER_TYPES.SEQUENCE);
//		
//		return a;
//	}
//
//	
//	/**
//	 * This method generates random activities, with this structure:
//	 * <pre>
//	 *      .-> o -> ? -> o -.
//	 *  o-> A                  B -> o
//	 *      `-> o -> ? -> o -'
//	 * </pre>
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @return the head activity
//	 */
//	private PlgActivity getPatternAnd(PlgParameters parameters, PlgActivity to, int maxNested) {
//		PlgActivity a = askNewActivity();
//		PlgActivity b = askNewActivity();
//		a.inAndUntil(b);
//		int totFork = 2 + generator.nextInt(parameters.getAndBranches() - 1);
//		int totEmpty = 0;
//		// at least one activity must be in one and branch 
//		a.addNext(askInternalPattern(parameters, b, maxNested, false));
//		for (int i = 0; i < totFork - 1; i++) {
//			PlgActivity inner = askInternalPattern(parameters, b, maxNested);
//			// we need to check if this is an empty pattern
//			if (inner.equals(b)) {
//				totEmpty++;
//			} else {
//				a.addNext(inner);
//			}
//		}
//		// we can have an empty activity for each branch; in this case we have
//		// to connect a with b
//		if (totEmpty == totFork) {
//			a.addNext(b);
//		}
//		// loop stuff
//		if (!a.equals(this.firstActivity) && !b.equals(this.lastActivity)) {
//			if (PlgParameters.randomFromPercent(parameters.getLoopPercent())) {
//				incrementPatternCounter(COUNTER_TYPES.LOOP);
//				getLoopPattern(parameters, a, b, maxNested);
////				b.addLoop(a);
//			}
//		}
//		if (to != null) {
//			b.addNext(to);
//		}
//		
//		// counter update
//		if (totFork-totEmpty > 1) {
//			// check current number of branches
//			int currentAndBranches = totFork-totEmpty;
//			int maxAndBranches = getPatternsCounter(COUNTER_TYPES.MAX_AND_BRANCHES);
//			if (currentAndBranches > maxAndBranches) {
//				setPatternCounter(COUNTER_TYPES.MAX_AND_BRANCHES, currentAndBranches);
//			}
//			// this is actually an AND pattern
//			incrementPatternCounter(COUNTER_TYPES.AND);
//		}
//		
//		return a;
//	}
//
//	
//	/**
//	 * This method generates random activities, with this structure:
//	 * <pre>
//	 *             .-> ? -.
//	 *   o -> A -> o       -> o -> B -> o
//	 *             `-> ? -'
//	 * </pre>
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @return the head activity
//	 */
//	private PlgActivity getPatternXor(PlgParameters parameters, PlgActivity to, int maxNested) {
//		PlgActivity a = askNewActivity();
//		PlgActivity b = askNewActivity();
//		a.inXorUntil(b);
//		int totFork = 2 + generator.nextInt(parameters.getXorBranches() - 1);
//		int totEmpty = 0;
//		// at least one activity must be in one and branch 
//		a.addNext(askInternalPattern(parameters, b, maxNested, false));
//		for (int i = 0; i < totFork - 1; i++) {
//			PlgActivity inner = askInternalPattern(parameters, b, maxNested);
//			// we need to check if this is an empty pattern
//			if (inner.equals(b)) {
//				totEmpty++;
//			} else {
//				a.addNext(inner);
//			}
//		}
//		// we can have an empty activity for each branch; in this case we have
//		// to connect a with b
//		if (totEmpty == totFork) {
//			a.addNext(b);
//		}
//		// loop stuff
//		if (!a.equals(this.firstActivity) && !b.equals(this.lastActivity)) {
//			if (PlgParameters.randomFromPercent(parameters.getLoopPercent())) {
//				incrementPatternCounter(COUNTER_TYPES.LOOP);
//				getLoopPattern(parameters, a, b, maxNested);
////				b.addLoop(a);
//			}
//		}
//		if (to != null) {
//			b.addNext(to);
//		}
//		
//		// counter update
//		if (totFork-totEmpty > 1) {
//			// check current number of branches
//			int currentXorBranches = totFork-totEmpty;
//			int maxXorBranches = getPatternsCounter(COUNTER_TYPES.MAX_XOR_BRANCHES);
//			if (currentXorBranches > maxXorBranches) {
//				setPatternCounter(COUNTER_TYPES.MAX_XOR_BRANCHES, currentXorBranches);
//			}
//			// this is actually a XOR pattern
//			incrementPatternCounter(COUNTER_TYPES.XOR);
//		}
//		
//		return a;
//	}
//	
//	
//	private PlgActivity getLoopPattern(PlgParameters parameters, PlgActivity from, PlgActivity to, int maxNested) {
//		from.inXorUntil(to);
//		from.addNext(askInternalPattern(parameters, to, maxNested, true));
//		return to;
//	}
//	
//	
//	/**
//	 * This method generates an empty activity pattern
//	 *
//	 * @param parameters the object with all the parameters
//	 * @param to the activity to point the last internal activity
//	 * @return the head activity
//	 */
//	private PlgActivity getEmptyPatter(PlgParameters parameters, PlgActivity to, int maxNested) {
//		// counter update
//		incrementPatternCounter(COUNTER_TYPES.EMPTY);
//		
//		return to;
//	}
	/* ********************************************************************** */
	
	
	/* ********************************************************************** */
	/*                           Metric calculation                           */
	/* ********************************************************************** */
	/**
	 * This method builds the default metric of the process
	 * 
	 * @see PlgMetricCalculator
	 * @return the object that collect all the metric values of the process
	 * @throws IOException
	 */
	public PlgProcessMeasures getProcessMeasures() throws IOException {
		if (metrics != null) {
			return metrics;
		}
		
		PlgMetricCalculator calculator = new PlgMetricCalculator(this);
		try {
			metrics = calculator.calculate();
			return metrics;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	
	/**
	 * This method to get the Extended Cyclomatic Metric value
	 * 
	 * @return the metric value
	 */
	public int getProcessCyclomaticMetric() {
		if (metrics != null) {
			return metrics.getCyclomaticMetric();
		}
		
		try {
			PetriNet p = getPetriNet();
			return PlgMetricCalculator.calculateCyclomaticMetric(p).first;
		} catch (IOException e) {
			
		}
		return -1;
	}
	
	
	/**
	 * This method to get the Extended Cardoso Metric value
	 * 
	 * @return the metric value
	 */
	public int calculateCardosoMetric() {
		if (metrics != null) {
			return metrics.getCardosoMetric();
		}
		
		try {
			PetriNet p = getPetriNet();
			return PlgMetricCalculator.calculateCardosoMetric(p).first;
		} catch (IOException e) {
			
		}
		return -1;
	}
	/* ********************************************************************** */
}
