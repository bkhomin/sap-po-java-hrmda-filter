package ru.sap.po.mapping.hrmd.filter;

import org.xml.sax.SAXException;
import ru.sap.po.mapping.hrmd.filter.config.FilterPropertiesHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sap.aii.mapping.api.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class HRMD_to_HRMD_filter extends AbstractTransformation {

	/**
	 * Constant represents the Last Day on Earth according to SAP.
	 * Is used to determine the most actual time-dependent infosegments.
	 */
	private final String lastSapDayOnEarth = "99991231";

	/**
	 * Constant represents the custom namespace of Dynamic Configuration key.
	 * DC is used to store 'BUKRS'-'ReceiverSystem' pairs.
	 */
	private final String dcKeyNamespace = "urn:rn:COMMON:HR:EmployeeDataDistribution:10";

	/**
	 * List of <tt>HRMD_A09</tt> organizational management infotypes.
	 *
	 * Can be modified in "filter.properties" file.
	 */
	private List<String> MANAGEMENT_INFOTYPES;

	/**
	 * List of <tt>HRMD_A09</tt> employee infotypes.
	 *
	 * Can be modified in "filter.properties" file.
	 */
	private List<String> EMPLOYEE_INFOTYPES;

	@Override
	public void transform (TransformationInput ti, TransformationOutput to)
			throws StreamTransformationException {

		getTrace().addInfo("HRMD_A filtration mapping program started!");

		// Try to load mapping properties from file - if it fails, we'll stop the whole transformation
		if(!loadProperties()) return;

		// Parse incoming message to DOM <code>Document</code>
		Document source = getDocumentFromTransformationInput(ti);

		// If parsing failed - there's nothing to process, we'll stop the whole transformation
		if (source == null) return;

		// Remove unnecessary infotypes from incoming message
		processInfotypesFiltration(source);

		// Remain only receiver-relevant persons in target message and clean persons full names
		processPersonsFiltration(source, ti.getDynamicConfiguration(), ti.getInputHeader());

		// Write result message to TransformationOutput
		writeXMLToTransformationOutput(to, getXmlStringFromDocument(source));

		getTrace().addInfo("HRMD_A filtration mapping program finished!");
	}

	/**
	 * Method walks through source {@link Document} (HRMD_A IDoc) and checks each <code>E1PITYP</code>
	 * {@link Node} infotype (<code>INFTY</code> tag). If the value of <code>INFTY</code> tag
	 * is contained in one of {@link #EMPLOYEE_INFOTYPES} or {@link #MANAGEMENT_INFOTYPES} array -
	 * the whole <code>E1PITYP</code> {@link Node} wil be remained in the DOM tree.
	 *
	 * All <code>E1PITYP</code> nodes with unrecognized <code>INFTY</code> codes will be
	 * REMOVED from the DOM tree (and therefore in target message).
	 *
	 * @param source  source HRMD_A IDoc message, serialized to {@link Document}
	 */
	private void processInfotypesFiltration(Document source) {
		// List of all <tt>HRMD_A09</tt> infotypes which must be passed through this mapping.
		List<String> inftyToPass = new ArrayList<>();
		inftyToPass.addAll(EMPLOYEE_INFOTYPES);
		inftyToPass.addAll(MANAGEMENT_INFOTYPES);

		getTrace().addDebugMessage(inftyToPass.toString());

		// Get all <tt>E1PITYP</tt> segments from the whole DOM tree to NodeList
		NodeList infoTypes = source.getElementsByTagName("E1PITYP");

		getTrace().addDebugMessage("Source IDOC message has " + infoTypes.getLength() + " info segments.");

		// Open stream from NodeList and put all of it's records in List that wouldn't be modified during filtration
		List<Node> nodes = IntStream.range(0, infoTypes.getLength())
				.mapToObj(infoTypes::item)
				.collect(Collectors.toList());

		// Iterate over list of collected <tt>E1PITYP</tt> nodes
		nodes.forEach(node -> {
			if (node.getNodeType() != Node.ELEMENT_NODE) return;
			Element infotype = (Element) node;

			// Try to get <tt>OBJID</tt> string for segment (only for logging purpose)
			String objId = getTextContentFromElementTag(infotype, "OBJID");

			// Try to get <tt>INFTY</tt> string for segment
			String infoTypeCode = getTextContentFromElementTag(infotype, "INFTY");

			// Go to next iteration, if there's no <tt>INFTY</tt> string
			if (isNullOrEmpty(infoTypeCode)) return;

			// Perform check for needed infotypes
			if (!inftyToPass.contains(infoTypeCode)) {
				infotype.getParentNode().removeChild(infotype);
				getTrace().addInfo("Found segment with INFTY: '" + infoTypeCode + "' and OBJID: '" +
						objId + "', so the whole parent 'E1PITYP' element would be removed from target message.");
			}
		});

		source.normalize();
	}

	/**
	 * Method firstly gets <tt>ReceiverService</tt> string from {@link InputHeader} object,
	 * then walks through source {@link Document} (HRMD_A IDoc) with the following logic: <br>
	 *     1) Iterate over list of all <code>E1PITYP</code> nodes of source {@link Document} <br>
	 *     2) If <code>INFTY</code> element in {@link Node} has value '0001' - get all time
	 *     dependent segments (<code>E1P0001</code>) <br>
	 *     3) Check if time dependent segment is active for now - compare {@link #lastSapDayOnEarth}
	 *     constant with the value of <code>ENDDA</code> element of time dependent segment and continue
	 *     processing if  comparing values equals each other <br>
	 *     4) Get value of <code>BUKRS</code> element of time dependent segment - it's employee current
	 *     company code <br>
	 *     5) Try to get appropriate <code>SystemID</code> for given <code>BUKRS</code>
	 *     from {@link DynamicConfiguration} that was filled on receiver determination step <br>
	 *     6) Check if found <code>SystemID</code> from {@link DynamicConfiguration} equals
	 *     <code>ReceiverService</code> from {@link InputHeader}: <br>
	 *         a) Equals - remain parent {@link Node} in target message and continue processing -
	 *         pass parent {@link Node} (<code>E1PLOGI</code>) to {@link #processPersonFullNameCorrection(Node)} <br>
	 *         b) Not equals - remove parent {@link Node} from target message <br>
	 *
	 * @param source  source HRMD_A IDoc message, serialized to {@link Document}
	 * @param dc      {@link DynamicConfiguration} object
	 * @param ih      {@link InputHeader} object
	 */
	private void processPersonsFiltration(Document source, DynamicConfiguration dc, InputHeader ih) {
		// Get current message receiver service
		String currentSystemId = ih.getReceiverService();

		// If current receiver service is null or empty - we can not go on
		if (isNullOrEmpty(currentSystemId)) {
			getTrace().addWarning("Can not get ReceiverService for current message from InputHeader object." +
					" Can not perform person bu company code filtration.");
			return;
		}

		// Get all <tt>E1PITYP</tt> segments from the whole DOM tree (it's been modified on previous steps)
		NodeList infoTypes = source.getElementsByTagName("E1PITYP");

		getTrace().addDebugMessage("Source IDOC message has " + infoTypes.getLength() + " info segments.");

		// Open stream from NodeList and put all of it's records in List that wouldn't be modified during filtration
		List<Node> nodes = IntStream.range(0, infoTypes.getLength())
				.mapToObj(infoTypes::item)
				.collect(Collectors.toList());

		// Iterate through list of collected <tt>E1PITYP</tt> nodes
		nodes.forEach(node -> {
			if (node.getNodeType() != Node.ELEMENT_NODE) return;
			Element infoType = (Element) node;

			// Try to get <tt>OBJID</tt> string for segment (only for logging purpose)
			String objId = getTextContentFromElementTag(infoType, "OBJID");

			// Try to get <tt>INFTY</tt> string for segment
			String infoTypeCode = getTextContentFromElementTag(infoType, "INFTY");

			// Go to next iteration, if there's no <tt>INFTY</tt> string
			if (isNullOrEmpty(infoTypeCode)) return;

			// If '0001' infotype is found, get company code and then try to get SystemID from DynamicConfiguration
			if ("0001".equals(infoTypeCode)) {
				NodeList timeDependentSegmentsE1P = infoType.getElementsByTagName("E1P" + infoTypeCode);

				// Open stream from NodeList and put all of it's records in List that wouldn't be modified during filtration
				List<Node> timeDependentSegmentsListE1P = IntStream.range(0, timeDependentSegmentsE1P.getLength())
						.mapToObj(timeDependentSegmentsE1P::item)
						.collect(Collectors.toList());

				// Iterate over list of collected <tt>E1P0001</tt> nodes
				timeDependentSegmentsListE1P.forEach(E1P0001 -> {
					if (E1P0001.getNodeType() != Node.ELEMENT_NODE) return;
					Element timeDependentSegmentE1P = (Element) E1P0001;

					// Try to get value of "ENDDA" element to check if this segment is valid for now
					String endDate = getTextContentFromElementTag(timeDependentSegmentE1P, "ENDDA");
					if(!lastSapDayOnEarth.equals(endDate)) return;

					// Try to get company code from active time dependent segment
					String companyCode = getTextContentFromElementTag(timeDependentSegmentE1P, "BUKRS");
					if (!isNullOrEmpty(companyCode)) {
						// Get SystemId appropriate to BUKRS from Dynamic Configuration
						DynamicConfigurationKey bukrsKey =
								DynamicConfigurationKey.create(dcKeyNamespace, "R" + companyCode);
						String systemId = dc.get(bukrsKey);

						// Get parent node of infotype (it's 'E1PLOGI') for further processing
						Node infoTypeParent = node.getParentNode();

						// If there is a SystemId in Dynamic Configuration - this is relevant BUKRS
						if (!isNullOrEmpty(systemId)) {
							// If that SystemId is the same as ReceiverService - need to keep this person in target message
							if (systemId.equals(currentSystemId)) {
								// If this person is kept - it must be processed further
								processPersonFullNameCorrection(infoTypeParent);
								getTrace().addInfo("Found relevant person data with BUKRS: '" + companyCode + "' and OBJID: '" +
										objId + "' - keep this person in target message that goes to system: '" + currentSystemId + "'.");
								return;
							}
						}

						// If all checks above was false - remove this person from target message
						infoTypeParent.getParentNode().removeChild(infoTypeParent);
						getTrace().addInfo("Found person data with BUKRS: '" + companyCode + "' and OBJID: '" +
								objId + "' that is irrelevant for receiver system: '" + currentSystemId + "', so " +
								"the whole 'E1PLOGI' element would be removed from target message.");
					}
				});
			}
		});

		source.normalize();
	}

	/**
	 * Method works with <code>E1PLOGI</code> {@link Node} that contains employee info.
	 *
	 * @param infoTypeParentNode  parent node of infotype (it's "E1PLOGI" element)
	 */
	private void processPersonFullNameCorrection(Node infoTypeParentNode) {
		if (infoTypeParentNode.getNodeType() != Node.ELEMENT_NODE) return;
		Element infoTypeParent = (Element) infoTypeParentNode;

		// Get all <tt>E1PITYP</tt> segments from the whole DOM tree (it's been modified on previous steps)
		NodeList infoTypes = infoTypeParent.getElementsByTagName("E1PITYP");

		// Declare variable to remember <tt>E1PITYP</tt> segment with the value '0001' in <tt>INFTY</tt> tag
		Element it0001 = null;
		// Full name StringBuilder initialization
		StringBuilder fullNameSb = new StringBuilder();

		// Iterate over infoTypes NodeList
		for (int i = 0; i < infoTypes.getLength(); i++) {
			if (infoTypes.item(i).getNodeType() != Node.ELEMENT_NODE) continue;
			Element infoType = (Element) infoTypes.item(i);

			// Try to get <tt>INFTY</tt> string for segment
			String infoTypeCode = getTextContentFromElementTag(infoType, "INFTY");

			// Go to next iteration, if there's no <tt>INFTY</tt> string
			if (isNullOrEmpty(infoTypeCode)) continue;

			switch (infoTypeCode) {
				case "0001":
					// Segment with INFTY=0001 must appear only once in <tt>E1PLOGI</tt> and we must remember it for further processing
					it0001 = infoType;
					break;
				case "0002":
					// Segment with INFTY=0002 contains time dependent segments with employee personal data
					NodeList timeDependentSegmentsE1P = infoType.getElementsByTagName("E1P" + infoTypeCode);

					// Iterate over all time dependent segments with employee info
					for (int j = 0; j < timeDependentSegmentsE1P.getLength(); j++) {
						if (timeDependentSegmentsE1P.item(j).getNodeType() != Node.ELEMENT_NODE) return;
						Element timeDependentSegmentE1P = (Element) timeDependentSegmentsE1P.item(j);

						// Try to get employee data and the date till this segment is valid (ENDDA)
						String surname = getTextContentFromElementTag(timeDependentSegmentE1P, "NACHN");
						String name = getTextContentFromElementTag(timeDependentSegmentE1P, "VORNA");
						String middleName = getTextContentFromElementTag(timeDependentSegmentE1P, "MIDNM");
						String endDate = getTextContentFromElementTag(timeDependentSegmentE1P, "ENDDA");

						// Process employee surname
						if (!isNullOrEmpty(surname)) {
							// Remove all whitespaces
							surname = surname.trim();
							// If this particular time dependent segment is valid for now - append surname to full name
							if(lastSapDayOnEarth.equals(endDate)) fullNameSb.append(surname);
							// Set normalized surname to source DOM tree
							setTextContentToElementTag(timeDependentSegmentE1P, "NACHN", surname);
						}

						// Process employee first name
						if (!isNullOrEmpty(name)) {
							// Remain only first symbol from original name and put dot at the end
							name = name.charAt(0) + ".";
							// Make it upper case
							name = name.toUpperCase();
							// If this particular time dependent segment is valid for now - append name to full name
							if(lastSapDayOnEarth.equals(endDate)) fullNameSb.append(" ").append(name);
							// Set normalized name to source DOM tree
							setTextContentToElementTag(timeDependentSegmentE1P, "VORNA", name);
						}

						// Process employee middle name
						if (!isNullOrEmpty(middleName)) {
							// Remain only first symbol from original middle name and put dot at the end
							middleName = middleName.charAt(0) + ".";
							// Make it upper case
							middleName = middleName.toUpperCase();
							// If this particular time dependent segment is valid for now - append middle name to full name
							if(lastSapDayOnEarth.equals(endDate)) fullNameSb.append(" ").append(middleName);
							// Set normalized middle name to source DOM tree
							setTextContentToElementTag(timeDependentSegmentE1P, "MIDNM", middleName);
						}

						// Try to get nodes which must be REMOVED from source IDoc
						Node nachn40 = getTagNodeFromElement(timeDependentSegmentE1P, "NACHN_40");
						Node vorna40 = getTagNodeFromElement(timeDependentSegmentE1P, "VORNA_40");
						Node nchmc = getTagNodeFromElement(timeDependentSegmentE1P, "NCHMC");
						Node vnamc = getTagNodeFromElement(timeDependentSegmentE1P, "VNAMC");
						Node inits = getTagNodeFromElement(timeDependentSegmentE1P, "INITS");
						Node fnamr = getTagNodeFromElement(timeDependentSegmentE1P, "FNAMR");
						Node lnamr = getTagNodeFromElement(timeDependentSegmentE1P, "LNAMR");

						// If node not null - REMOVE it from DOM tree
						if (nachn40 != null) timeDependentSegmentE1P.removeChild(nachn40);
						if (vorna40 != null) timeDependentSegmentE1P.removeChild(vorna40);
						if (nchmc != null) timeDependentSegmentE1P.removeChild(nchmc);
						if (vnamc != null) timeDependentSegmentE1P.removeChild(vnamc);
						if (inits != null) timeDependentSegmentE1P.removeChild(inits);
						if (fnamr != null) timeDependentSegmentE1P.removeChild(fnamr);
						if (lnamr != null) timeDependentSegmentE1P.removeChild(lnamr);

						// Get NodeList of additional time dependent segments in 0002 infotype
						NodeList timeDependentSegmentsE1Q = timeDependentSegmentE1P.getElementsByTagName("E1Q" + infoTypeCode);

						// Iterate over each time dependent node
						for (int k = 0; k < timeDependentSegmentsE1Q.getLength(); k++) {
							if (timeDependentSegmentsE1Q.item(k).getNodeType() != Node.ELEMENT_NODE) return;
							Element timeDependentSegmentE1Q = (Element) timeDependentSegmentsE1Q.item(k);

							// Try to get nodes which must be REMOVED from source IDoc
							Node fnamr45 = getTagNodeFromElement(timeDependentSegmentE1Q, "FNAMR_45");
							Node lnamr45 = getTagNodeFromElement(timeDependentSegmentE1Q, "LNAMR_45");

							// If node not null - REMOVE it from DOM tree
							if (fnamr45 != null) timeDependentSegmentE1Q.removeChild(fnamr45);
							if (lnamr45 != null) timeDependentSegmentE1Q.removeChild(lnamr45);
						}
					}

					break;
			}
		}

		// Process segment with INFTY=0001 if it's not null
		if (it0001 != null) {
			// Obtain full name String
			String fullName = fullNameSb.toString();
			// Get NodeList with time dependent segments of 0001 infotype
			NodeList timeDependentSegmentsE1P = it0001.getElementsByTagName("E1P0001");

			// Open stream from NodeList and put all of it's records in List that wouldn't be modified during iteration
			List<Node> timeDependentSegmentsListE1P = IntStream.range(0, timeDependentSegmentsE1P.getLength())
					.mapToObj(timeDependentSegmentsE1P::item)
					.collect(Collectors.toList());

			// Iterate over List with collected time dependent segments
			timeDependentSegmentsListE1P.forEach(E1P0001 -> {
				if (E1P0001.getNodeType() != Node.ELEMENT_NODE) return;
				Element timeDependentSegmentE1P = (Element) E1P0001;

				// Set constructed earlier full name string to each time dependent segment (IT WILL REWRITE EXISTING NAME)
				setTextContentToElementTag(timeDependentSegmentE1P, "ENAME", fullName);
				setTextContentToElementTag(timeDependentSegmentE1P, "SNAME", fullName.toUpperCase());

				// TODO: REMOVE THIS CALL IF YOU WANT YOUR KOSTL (МВЗ) BACK!!!
				removeKostl(timeDependentSegmentE1P);
			});
		}
	}

	/**
	 * !!! TEMPORARY METHOD TO REMOVE KOSTL (МВЗ) DATA FROM TARGET IDOC !!!
	 *
	 * This method must be removed from production after special date (in april 2020).
	 *
	 * @param timeDependentSegmentE1P  Element that holds KOSTL (МВЗ)
	 */
	private void removeKostl(Element timeDependentSegmentE1P) {
		Node kostl = getTagNodeFromElement(timeDependentSegmentE1P, "KOSTL");

		if (kostl != null) {
			String pernr = getTextContentFromElementTag(timeDependentSegmentE1P, "PERNR");
			timeDependentSegmentE1P.removeChild(kostl);
			getTrace().addInfo("Removed KOSTL (МВЗ) from 0001 INFTY for PERNR: " + pernr + ".");
		}
	}

	/**
	 * Method loads mapping parameters from properties file in mapping resources.
	 * Will return <code>false</code>, if any error appear.
	 *
	 * @see FilterPropertiesHandler
	 *
	 * @return boolean
	 */
	private boolean loadProperties() {
		FilterPropertiesHandler propHandler = FilterPropertiesHandler.getInstance();

		MANAGEMENT_INFOTYPES = propHandler.getListPropertyValue("management.infotypes");
		if (MANAGEMENT_INFOTYPES == null) {
			getTrace().addWarning("Can't load Management Infotypes property from 'filter.properties' file.");
			return false;
		} else {
			getTrace().addDebugMessage("Loaded Management Infotypes property with value: '"
					+ Arrays.toString(MANAGEMENT_INFOTYPES.toArray()) + "' successfully");
		}

		EMPLOYEE_INFOTYPES = propHandler.getListPropertyValue("employee.infotypes");
		if (EMPLOYEE_INFOTYPES == null) {
			getTrace().addWarning("Can't load Employee Infotypes property from 'filter.properties' file.");
			return false;
		} else {
			getTrace().addDebugMessage("Loaded Employee Infotypes property with value: '"
					+ Arrays.toString(EMPLOYEE_INFOTYPES.toArray()) + "' successfully");
		}

		return true;
	}

	/**
	 * Method parses incoming message from {@link InputStream} in {@link TransformationInput}
	 * to DOM {@link Document} or returns null if any error occurs.
	 *
	 * @param ti  TransformationInput object instance
	 *
	 * @return Document
	 */
	private Document getDocumentFromTransformationInput(TransformationInput ti) {
		getTrace().addDebugMessage("Started to parse HRMD_A09 XML to DOM Document.");
		try (InputStream is = ti.getInputPayload().getInputStream()){
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(is);
			getTrace().addDebugMessage("Finished parsing of HRMD_A09 XML to DOM Document.");
			return doc;
		} catch (IOException ioe) {
			getTrace().addWarning("Encountered IOException during incoming message parsing ", ioe);
		} catch (ParserConfigurationException pce) {
			getTrace().addWarning("Encountered ParserConfigurationException during incoming message parsing ", pce);
		} catch (SAXException se) {
			getTrace().addWarning("Encountered SAXException during incoming message parsing ", se);
		}
		return null;
	}

	/**
	 * Method parses DOM {@link Document} to {@link String}
	 *
	 * @param doc  Document object instance
	 *
	 * @return String
	 */
	private String getXmlStringFromDocument(Document doc) {
		try {
			DOMSource domSource = new DOMSource(doc);
			StringWriter writer = new StringWriter();
			StreamResult result = new StreamResult(writer);
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.transform(domSource, result);
			return writer.toString();
		} catch(TransformerException ex) {
			getTrace().addWarning("Encountered TransformerException while trying to parse DOM Document to XML string ", ex);
			return null;
		}
	}

	/**
	 * Method writes XML message string after mapping into {@link OutputStream}
	 * in {@link TransformationOutput} object instance.
	 *
	 * @param to   TransformationOutput object instance
	 * @param xml  String target XML message payload
	 */
	private void writeXMLToTransformationOutput(TransformationOutput to, String xml) {
		try (OutputStream os = to.getOutputPayload().getOutputStream()) {
			os.write(xml.getBytes(StandardCharsets.UTF_8));
			getTrace().addDebugMessage("Finished writing result message to TransformationOutput");
		} catch (Exception e) {
			getTrace().addWarning("Encountered error during writing to TransformationOutput ", e);
		}
	}

	/**
	 * Utility method to get text content from tag inside given element.
	 *
	 * @param element  element to walk through
	 * @param tagName  name of tag with target text content
	 *
	 * @return String
	 */
	private String getTextContentFromElementTag(Element element, String tagName) {
		return Optional.ofNullable(element)
				.map(elem -> elem.getElementsByTagName(tagName))
				.map(chldrn -> chldrn.item(0))
				.map(Node::getTextContent)
				.orElse("");
	}

	/**
	 * Utility method to set text content to tag inside given DOM {@link Element}.
	 *
	 * @param element  element to walk through
	 * @param tagName  name of tag with target text content
	 * @param content  text content to set
	 */
	private void setTextContentToElementTag(Element element, String tagName, String content) {
		Node tagNode = Optional.ofNullable(element)
				.map(elem -> elem.getElementsByTagName(tagName))
				.map(chldrn -> chldrn.item(0))
				.orElse(null);

		if (tagNode == null || tagNode.getNodeType() != Node.ELEMENT_NODE) return;

		Element tag = (Element) tagNode;
		tag.setTextContent(content);
	}

	/**
	 * Utility method to get node tag from given DOM {@link Element}.
	 *
	 * @param element  element to walk through
	 * @param tagName  name of tag
	 *
	 * @return {@link Node}
	 */
	private Node getTagNodeFromElement(Element element, String tagName) {
		return Optional.ofNullable(element)
				.map(elem -> elem.getElementsByTagName(tagName))
				.map(chldrn -> chldrn.item(0))
				.orElse(null);
	}

	/**
	 * Utility method to check if string is null or empty.
	 *
	 * @param string  input String to check
	 *
	 * @return boolean
	 */
	private boolean isNullOrEmpty(String string) {
		return string == null || string.length() == 0;
	}

}