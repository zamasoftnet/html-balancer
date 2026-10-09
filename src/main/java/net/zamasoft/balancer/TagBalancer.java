package net.zamasoft.balancer;

import java.util.ArrayList;
import java.util.List;

import net.zamasoft.balancer.ElementProps.ElementProp;

import org.htmlunit.cyberneko.xerces.util.XMLAttributesImpl;
import org.htmlunit.cyberneko.xerces.xni.Augmentations;
import org.htmlunit.cyberneko.xerces.xni.NamespaceContext;
import org.htmlunit.cyberneko.xerces.xni.QName;
import org.htmlunit.cyberneko.xerces.xni.XMLAttributes;
import org.htmlunit.cyberneko.xerces.xni.XMLDocumentHandler;
import org.htmlunit.cyberneko.xerces.xni.XMLLocator;
import org.htmlunit.cyberneko.xerces.xni.XMLString;
import org.htmlunit.cyberneko.xerces.xni.XNIException;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLComponentManager;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLConfigurationException;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLDocumentFilter;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLDocumentSource;
import org.htmlunit.cyberneko.HTMLComponent;
import org.htmlunit.cyberneko.HTMLElements;
import org.htmlunit.cyberneko.filters.DefaultFilter;
import org.htmlunit.cyberneko.filters.NamespaceBinder;

/**
 * Balances tags in an HTML document. This component receives document events
 * and tries to correct many common mistakes that human (and computer) HTML
 * document authors make. This tag balancer can:
 * <ul>
 * <li>add missing parent elements;
 * <li>automatically close elements with optional end tags; and
 * <li>handle mis-matched inline element tags.
 * </ul>
 * <p>
 * This component recognizes the following features:
 * <ul>
 * <li>http://cyberneko.org/html/features/augmentations
 * <li>http://cyberneko.org/html/features/report-errors
 * <li>http://cyberneko.org/html/features/balance-tags/document-fragment
 * <li>http://cyberneko.org/html/features/balance-tags/ignore-outside-content
 * </ul>
 * <p>
 * This component recognizes the following properties:
 * <ul>
 * <li>http://cyberneko.org/html/properties/names/elems
 * <li>http://cyberneko.org/html/properties/names/attrs
 * <li>http://cyberneko.org/html/properties/error-reporter
 * </ul>
 * 
 * @see HTMLElements
 * 
 * @author Andy Clark
 * @author Marc Guillemot
 * @author Tatsuhiko Miyabe
 * 
 * @version $Id: TagBalancer.java 1539 2018-01-20 06:37:23Z miyabe $
 */
public class TagBalancer implements XMLDocumentFilter, HTMLComponent {
	//
	// Constants
	//

	// features

	/** Namespaces. */
	protected static final String NAMESPACES = "http://xml.org/sax/features/namespaces";

	/** Document fragment balancing only. */
	protected static final String DOCUMENT_FRAGMENT = "http://cyberneko.org/html/features/balance-tags/document-fragment";

	/** Recognized features. */
	private static final String[] RECOGNIZED_FEATURES = { NAMESPACES, DOCUMENT_FRAGMENT, };

	/** Recognized features defaults. */
	private static final Boolean[] RECOGNIZED_FEATURES_DEFAULTS = { null, Boolean.FALSE };

	// properties

	/** Modify HTML element names: { "upper", "lower", "default" }. */
	protected static final String NAMES_ELEMS = "http://cyberneko.org/html/properties/names/elems";

	/** Modify HTML attribute names: { "upper", "lower", "default" }. */
	protected static final String NAMES_ATTRS = "http://cyberneko.org/html/properties/names/attrs";

	/** Recognized properties. */
	private static final String[] RECOGNIZED_PROPERTIES = { NAMES_ELEMS, NAMES_ATTRS };

	/** Recognized properties defaults. */
	private static final Object[] RECOGNIZED_PROPERTIES_DEFAULTS = { null, null, };

	// modify HTML names

	/** Don't modify HTML names. */
	protected static final short NAMES_NO_CHANGE = 0;

	/** Match HTML element names. */
	protected static final short NAMES_MATCH = 0;

	/** Uppercase HTML names. */
	protected static final short NAMES_UPPERCASE = 1;

	/** Lowercase HTML names. */
	protected static final short NAMES_LOWERCASE = 2;

	//
	// Data
	//

	// features

	/** Namespaces. */
	protected boolean fNamespaces;

	/** Document fragment balancing only. */
	protected boolean fDocumentFragment;

	// properties

	private final XMLAttributes fEmptyAttrs = new XMLAttributesImpl();

	/** Modify HTML element names. */
	protected short fNamesElems;

	/** Modify HTML attribute names. */
	protected short fNamesAttrs;

	protected ElementProps fElementProps;

	// connections

	/** The document source. */
	protected XMLDocumentSource fDocumentSource;

	/** The document handler. */
	protected DefaultFilter fDocumentHandler = new DefaultFilter();

	// state

	/** The element stack. */
	protected final InfoStack fElementStack = new InfoStack();

	/** True if seen anything. Important for xml declaration. */
	protected boolean fSeenAnything;

	/** True if the document type declaration has been seen. */
	protected boolean fSeenDoctype;

	/** True if root element has been seen. */
	protected boolean fSeenRootElement;

	/** True if the &lt;head&gt; element has been seen. */
	protected boolean fSeenHeadElement;

	/** True if the &lt;body&gt; element has been seen. */
	protected boolean fSeenBodyElement;

	/**
	 * Quirks mode, as the HTML Standard derives it from the doctype (none, or a legacy one it lists). Only there does
	 * a table start tag leave an open p open (2026-10-09).
	 */
	private boolean fQuirks;

	/**
	 * The HTML Standard's form element pointer: the last form started, until a form end tag clears it (2026-10-09).
	 * A form start tag is ignored while it is set, even after the form was closed by other end tags.
	 */
	private Info fFormPointer;

	// temp vars

	private XNIRecorder fRecorder = new XNIRecorder();

	/** The locator handed on, which reports the positions of the held-back events of a table (2026-10-09). */
	private HeldLocator fLocator;

	public TagBalancer() {
		this.fElementProps = ElementProps.getElementProps("legacy.xml");
	}

	/**
	 * Returns the locator this balancer hands on with the start of the document: the scanner's, except that the events
	 * of a table it held back report the positions they were read at (2026-10-09). Handlers after the balancer that take
	 * source positions from the locator at each event should use this one.
	 *
	 * @return the locator, or null before the document starts
	 */
	public XMLLocator getLocator() {
		return this.fLocator;
	}

	public void setElementProps(ElementProps props) {
		this.fElementProps = props;
	}

	public ElementProps getElementProps() {
		return this.fElementProps;
	}

	//
	// HTMLComponent methods
	//

	/** Returns the default state for a feature. */
	public Boolean getFeatureDefault(String featureId) {
		int length = RECOGNIZED_FEATURES != null ? RECOGNIZED_FEATURES.length : 0;
		for (int i = 0; i < length; i++) {
			if (RECOGNIZED_FEATURES[i].equals(featureId)) {
				return RECOGNIZED_FEATURES_DEFAULTS[i];
			}
		}
		return null;
	} // getFeatureDefault(String):Boolean

	/** Returns the default state for a property. */
	public Object getPropertyDefault(String propertyId) {
		int length = RECOGNIZED_PROPERTIES != null ? RECOGNIZED_PROPERTIES.length : 0;
		for (int i = 0; i < length; i++) {
			if (RECOGNIZED_PROPERTIES[i].equals(propertyId)) {
				return RECOGNIZED_PROPERTIES_DEFAULTS[i];
			}
		}
		return null;
	} // getPropertyDefault(String):Object

	//
	// XMLComponent methods
	//

	/** Returns recognized features. */
	public String[] getRecognizedFeatures() {
		return RECOGNIZED_FEATURES;
	} // getRecognizedFeatures():String[]

	/** Returns recognized properties. */
	public String[] getRecognizedProperties() {
		return RECOGNIZED_PROPERTIES;
	} // getRecognizedProperties():String[]

	/** Resets the component. */
	public void reset(XMLComponentManager manager) throws XMLConfigurationException {
		// get features
		this.fNamespaces = manager.getFeature(NAMESPACES);
		this.fDocumentFragment = manager.getFeature(DOCUMENT_FRAGMENT);

		// get properties
		this.fNamesElems = getNamesValue(String.valueOf(manager.getProperty(NAMES_ELEMS)));
		this.fNamesAttrs = getNamesValue(String.valueOf(manager.getProperty(NAMES_ATTRS)));
	} // reset(XMLComponentManager)

	/** Sets a feature. */
	public void setFeature(String featureId, boolean state) throws XMLConfigurationException {

	} // setFeature(String,boolean)

	/** Sets a property. */
	public void setProperty(String propertyId, Object value) throws XMLConfigurationException {

		if (propertyId.equals(NAMES_ELEMS)) {
			this.fNamesElems = getNamesValue(String.valueOf(value));
			return;
		}

		if (propertyId.equals(NAMES_ATTRS)) {
			this.fNamesAttrs = getNamesValue(String.valueOf(value));
			return;
		}

	} // setProperty(String,Object)

	//
	// XMLDocumentSource methods
	//

	/** Sets the document handler. */
	public void setDocumentHandler(XMLDocumentHandler handler) {
		final RecoderFilter filter = new RecoderFilter();
		filter.setDocumentHandler(handler);
		this.fDocumentHandler = filter;
	} // setDocumentHandler(XMLDocumentHandler)

	// @since Xerces 2.1.0

	/** Returns the document handler. */
	public XMLDocumentHandler getDocumentHandler() {
		return this.fDocumentHandler.getDocumentHandler();
	} // getDocumentHandler():XMLDocumentHandler

	//
	// XMLDocumentHandler methods
	//

	// since Xerces-J 2.2.0

	/** Start document. */
	public void startDocument(XMLLocator locator, String encoding, NamespaceContext nscontext, Augmentations augs)
			throws XNIException {
		// reset state
		this.fElementStack.top = 0;
		this.fSeenAnything = false;
		this.fSeenDoctype = false;
		this.fSeenRootElement = false;
		this.fSeenHeadElement = false;
		this.fSeenBodyElement = false;
		this.fQuirks = true;
		this.fFormPointer = null;
		if (!this.fDocumentFragment) {
			this.fRecorder.mark();
		}

		// pass on event
		this.fLocator = locator == null ? null : new HeldLocator(locator);
		this.fDocumentHandler.startDocument(this.fLocator, encoding, nscontext, augs);

	} // startDocument(XMLLocator,String,Augmentations)

	// old methods

	/** XML declaration. */
	public void xmlDecl(String version, String encoding, String standalone, Augmentations augs) throws XNIException {
		this.fDocumentHandler.getDocumentHandler().xmlDecl(version, encoding, standalone, augs);
	} // xmlDecl(String,String,String,Augmentations)

	/** Doctype declaration. */
	public void doctypeDecl(String rootElementName, String publicId, String systemId, Augmentations augs)
			throws XNIException {
		this.fSeenAnything = true;
		if (!this.fSeenRootElement && !this.fSeenDoctype) {
			this.fSeenDoctype = true;
			this.fQuirks = isQuirksDoctype(rootElementName, publicId, systemId);
			this.fDocumentHandler.getDocumentHandler().doctypeDecl(rootElementName, publicId, systemId, augs);
		}
	} // doctypeDecl(String,String,String,Augmentations)

	/** End document. */
	public void endDocument(Augmentations augs) throws XNIException {
		// handle empty document
		if (!this.fSeenBodyElement && !this.fDocumentFragment) {
			final QName body = this.createQName("body");
			this.startElement(body, null, null);
		}

		// pop all remaining elements
		int length = this.fElementStack.top;
		for (int i = 0; i < length; i++) {
			this.callEndElement(this.fElementStack.pop());
		}

		// call handler
		this.fDocumentHandler.getDocumentHandler().endDocument(augs);
	} // endDocument(Augmentations)

	/** Comment. */
	public void comment(XMLString text, Augmentations augs) throws XNIException {
		this.fSeenAnything = true;
		if (!this.fSeenBodyElement) {
			this.fDocumentHandler.getDocumentHandler().comment(text, augs);
			return;
		}
		final XMLDocumentHandler out = contentOut(this.fElementStack.top >= 1 ? this.fElementStack.peek() : null);
		if (out != null) {
			out.comment(text, augs);
			return;
		}
		this.fDocumentHandler.comment(text, augs);
	} // comment(XMLString,Augmentations)

	/** Processing instruction. */
	public void processingInstruction(String target, XMLString data, Augmentations augs) throws XNIException {
		this.fSeenAnything = true;
		if (!this.fSeenBodyElement) {
			this.fDocumentHandler.getDocumentHandler().processingInstruction(target, data, augs);
			return;
		}
		final XMLDocumentHandler out = contentOut(this.fElementStack.top >= 1 ? this.fElementStack.peek() : null);
		if (out != null) {
			out.processingInstruction(target, data, augs);
			return;
		}
		this.fDocumentHandler.processingInstruction(target, data, augs);
	} // processingInstruction(String,XMLString,Augmentations)

	private boolean inHead() {
		for (int i = this.fElementStack.top - 1; i >= 0; --i) {
			Info parent = this.fElementStack.data[i];
			if (parent.prop.code == HTMLElements.HEAD) {
				return true;
			}
		}
		return false;
	}

	/** Start element. */
	public void startElement(final QName element, XMLAttributes attrs, final Augmentations augs) throws XNIException {
		this.fSeenAnything = true;
		this.normalizeAttributes(attrs);
		final ElementProp prop = this.getElementProp(element);

		if (prop.code == HTMLElements.HTML) {
			if (this.fSeenRootElement) {
				return;
			}
			this.directStartElement(prop, element, attrs, augs);
			this.fSeenRootElement = true;
			return;
		}
		if (prop.code == HTMLElements.HEAD) {
			if (this.fSeenHeadElement) {
				return;
			}
			if (!this.fSeenRootElement) {
				final QName html = this.createQName("html");
				this.directStartElement(this.getElementProp(html), html, this.emptyAttributes(), null);
				this.fSeenRootElement = true;
			}
			this.directStartElement(prop, element, attrs, augs);
			this.fSeenHeadElement = true;
			return;
		}

		if (prop.is(ElementProps.FLAG_BODY)) {
			// Start body
			if (this.fSeenBodyElement) {
				return;
			}
			if (this.fDocumentFragment) {
				this.directStartElement(prop, element, attrs, augs);
				return;
			}
			if (!this.fSeenRootElement) {
				final QName html = this.createQName("html");
				this.directStartElement(this.getElementProp(html), html, this.emptyAttributes(), null);
				this.fSeenRootElement = true;
			}
			if (!this.fSeenHeadElement) {
				XMLDocumentHandler handler = this.fDocumentHandler.getDocumentHandler();
				final QName head = this.createQName("head");
				handler.startElement(head, this.emptyAttributes(), null);
				handler.endElement(head, null);
				this.fSeenHeadElement = true;
			}
			while (this.fElementStack.peek().prop.code != HTMLElements.HTML) {
				this.directEndElement(null);
			}
			this.directStartElement(prop, element, attrs, augs);
			this.fSeenBodyElement = true;
			this.fRecorder.refeed(this.fDocumentHandler);
			return;
		}

		// Supply the missing head
		if (!this.fDocumentFragment && !this.fSeenBodyElement && !prop.is(ElementProps.FLAG_HEAD)
				&& (this.fSeenHeadElement || prop.code != HTMLElements.UNKNOWN)) {
			// Starting a non-head element implicitly opens body (the HTML Standard transition
			// from "before head"/"in head" to "in body").
			// Once head is established, move all elements, including unknown ones, into body as before
			// (renders custom tags in head as body text; covered by 1100-HEAD/unknown).
			// Only before head, do not trigger this for unknown elements: opening body for a malformed
			// unknown root (such as <html">) causes subsequent style/link/title elements to bypass head
			// processing and lose styles (0120-flow/012-margin).
			// Previously, this completion only applied to documents where head had been seen. In fragments
			// without body, elements flowed unwrapped outside html/body, and downstream synthesis injected
			// html/head/body inside open elements, corrupting the entire tree (observed in e-Gov legislation
			// HTML: styles shifted by one element; all ancestors remained open, breaking streaming
			// and causing OOM. 2026-08-09).
			final QName body = this.createQName("body");
			this.startElement(body, null, null);
		} else if (!this.fSeenHeadElement && prop.is(ElementProps.FLAG_HEAD)) {
			final QName head = this.createQName("head");
			this.startElement(head, this.emptyAttributes(), null);
		}

		// Check the parent element
		if (this.fElementStack.top >= 1) {
			{
				Info parent = this.fElementStack.peek();
				if (!this.fSeenBodyElement && (this.inHead() || parent.prop.is(ElementProps.FLAG_HEAD))) {
					this.directStartElement(prop, element, attrs, augs);
					return;
				}
			}
			this.closeRemovedForms();
			if (prop.code == HTMLElements.FORM && this.fFormPointer != null) {
				// "in body": a form start tag is ignored while the form element pointer is set, also when that
				// form was closed by an end tag other than </form> (lwn.net: its login form went into the comment
				// form left open in an earlier copy of the page, so Chrome has no form around the fields).
				return;
			}
			if (prop.code == HTMLElements.TABLE && !this.fQuirks) {
				// Outside quirks mode a table closes an open p ("close a p element"; w3c.org/Style/Examples).
				this.closeParagraphInButtonScope();
			}

			if (prop.contains(ElementProps.SET_DIGS_FOR)) {
				// Unwind the ancestor stack until the required parent is reached
				int close = 0;
				for (int i = this.fElementStack.top - 1; i >= 0; --i) {
					final Info info = this.fElementStack.data[i];
					if (info.prop.code == HTMLElements.HTML || info.prop.code == HTMLElements.HEAD) {
						// Do not close the HTML tag until DocumentEnd
						// Do not close the HEAD tag until content starts
						break;
					}
					if (prop.contains(ElementProps.SET_DIGS_FOR, info.prop.code)) {
						close = this.fElementStack.top - i - 1;
						break;
					}
				}
				for (int i = 0; i < close; ++i) {
					final Info info = this.fElementStack.pop();
					this.callEndElement(info);
				}
			}

			if (prop.contains(ElementProps.SET_OPEN_CLOSES)) {
				// Close the specified parent
				int close = 0;
				for (int i = this.fElementStack.top - 1; i >= 0; --i) {
					final Info info = this.fElementStack.data[i];
					if (info.prop.code == HTMLElements.HTML || info.prop.code == HTMLElements.HEAD) {
						// Do not close the HTML tag until DocumentEnd
						// Do not close the HEAD tag until content starts
						break;
					}
					if (prop.contains(ElementProps.SET_OPEN_CLOSES, info.prop.code)) {
						close = this.fElementStack.top - i;
					}
					if (prop.contains(ElementProps.SET_STOP_CLOSE_BY)
							&& prop.contains(ElementProps.SET_STOP_CLOSE_BY, info.prop.code)) {
						// Stop searching
						break;
					}
					if (prop.contains(ElementProps.SET_DIGS_FOR)
							&& prop.contains(ElementProps.SET_DIGS_FOR, info.prop.code)) {
						// Stop searching
						break;
					}
				}
				for (int i = 0; i < close; ++i) {
					final Info info = this.fElementStack.pop();
					this.callEndElement(info);
				}
			}
		}
		if (this.fElementStack.top >= 1) {
			{
				Info parent = this.fElementStack.peek();
				// Ignore the start tag directly inside the parent element
				if (parent.prop.contains(ElementProps.SET_DISCARDS_OPEN)
						&& parent.prop.contains(ElementProps.SET_DISCARDS_OPEN, prop.code)) {
					return;
				}
			}

			{
				final Info parent = this.fElementStack.peek();
				// Insert the required parent
				if (prop.contains(ElementProps.SET_INSERT_PARENTS)) {
					if (!prop.contains(ElementProps.SET_INSERT_PARENTS, parent.prop.code)) {
						String parentName = ElementProps.getHTMLElementName(prop.tags[ElementProps.SET_INSERT_PARENTS][0]);
						final QName parentTag = this.createQName(parentName);
						this.startElement(parentTag, this.emptyAttributes(), null);
					}
				}
			}
		}

		// Close and reopen
		List<Info> continueTags = null;
		if (prop.contains(ElementProps.SET_OPEN_SPLITS)) {
			while (this.fElementStack.top >= 1) {
				Info info = this.fElementStack.peek();
				if (!prop.contains(ElementProps.SET_OPEN_SPLITS, info.prop.code)) {
					break;
				}
				info = this.fElementStack.pop();
				this.callEndElement(info);
				if (continueTags == null) {
					continueTags = new ArrayList<Info>();
				}
				continueTags.add(info);
			}
		}

		// call handler
		final Info parentInfo = this.fElementStack.top >= 1 ? this.fElementStack.peek() : null;
		final XMLDocumentHandler out = fosters(parentInfo, prop, attrs) ? this.fosterOut()
				: contentOut(parentInfo);
		if (prop.is(ElementProps.FLAG_EMPTY)) {
			if (attrs == null) {
				attrs = this.emptyAttributes();
			}
			if (out == null) {
				this.fDocumentHandler.emptyElement(element, attrs, augs);
			} else {
				out.emptyElement(element, attrs, augs);
			}
		} else {
			final Info info = new Info(prop, element, attrs);
			info.startOut = out;
			info.contentOut = out;
			info.dropsLineFeed = prop.code == HTMLElements.TEXTAREA;
			if (prop.code == HTMLElements.TABLE) {
				info.buffer = new TableBuffer(out == null ? this.fDocumentHandler : out, this.fLocator);
				info.contentOut = info.buffer;
			}
			this.fElementStack.push(info);
			if (prop.code == HTMLElements.FORM) {
				this.fFormPointer = info;
			}
			if (attrs == null) {
				attrs = this.emptyAttributes();
			}
			if (info.buffer != null) {
				info.buffer.startElement(element, attrs, augs);
			} else if (out == null) {
				this.fDocumentHandler.getDocumentHandler().startElement(element, attrs, augs);
			} else {
				out.startElement(element, attrs, augs);
			}
		}

		if (continueTags != null) {
			for (int i = continueTags.size() - 1; i >= 0; --i) {
				Info info = (Info) continueTags.get(i);
				this.startElement(info.qname, info.atts, null);
			}
		}
	}// startElement(QName,XMLAttributes,Augmentations)

	private QName createQName(String tagName) {
		tagName = modifyName(tagName, this.fNamesElems);
		return new QName(null, tagName, tagName, null);
	}

	/** Empty element. */
	public void emptyElement(final QName elem, XMLAttributes attrs, Augmentations augs) throws XNIException {
		this.startElement(elem, attrs, augs);
		final ElementProp prop = this.getElementProp(elem);
		if (!prop.is(ElementProps.FLAG_EMPTY)) {
			this.endElement(elem, augs);
		}
	} // emptyElement(QName,XMLAttributes,Augmentations)

	/** Start CDATA section. */
	public void startCDATA(Augmentations augs) throws XNIException {
		this.fSeenAnything = true;
		if (!this.fSeenBodyElement && this.fElementStack.top >= 1) {
			Info parent = this.fElementStack.peek();
			if (parent.prop.is(ElementProps.FLAG_HEAD)) {
				this.fDocumentHandler.getDocumentHandler().startCDATA(augs);
				return;
			}
		}

		// call handler
		final XMLDocumentHandler out = contentOut(this.fElementStack.top >= 1 ? this.fElementStack.peek() : null);
		if (out != null) {
			out.startCDATA(augs);
			return;
		}
		this.fDocumentHandler.startCDATA(augs);
	} // startCDATA(Augmentations)

	/** End CDATA section. */
	public void endCDATA(Augmentations augs) throws XNIException {
		if (!this.fSeenBodyElement && this.fElementStack.top >= 1) {
			Info parent = this.fElementStack.peek();
			if (parent.prop.is(ElementProps.FLAG_HEAD)) {
				this.fDocumentHandler.getDocumentHandler().endCDATA(augs);
				return;
			}
		}

		// call handler
		final XMLDocumentHandler out = contentOut(this.fElementStack.top >= 1 ? this.fElementStack.peek() : null);
		if (out != null) {
			out.endCDATA(augs);
			return;
		}
		this.fDocumentHandler.endCDATA(augs);
	} // endCDATA(Augmentations)

	/** Characters. */
	public void characters(XMLString text, final Augmentations augs) throws XNIException {
		if (this.fElementStack.top >= 1 && this.fElementStack.peek().dropsLineFeed) {
			this.fElementStack.peek().dropsLineFeed = false;
			if (text.length() > 0 && text.charAt(0) == '\n') {
				if (text.length() == 1) {
					return;
				}
				text = new XMLString(text.toString().substring(1));
			}
		}
		if (!this.fDocumentFragment) {
			// handle bare characters
			if (!this.fSeenAnything) {
				if (isWhitespace(text)) {
					return;
				}
			}
		}
		this.fSeenAnything = true;

		// Check the parent element
		this.closeRemovedForms();
		if (this.fElementStack.top >= 1) {
			Info parent = this.fElementStack.peek();
			// Insert the required parent
			if (parent.prop.contains(ElementProps.SET_INSERT_BY_TEXT)) {
				if (!isWhitespace(text)) {
					final String parentName = ElementProps
							.getHTMLElementName(parent.prop.tags[ElementProps.SET_INSERT_BY_TEXT][0]);
					final QName parentTag = this.createQName(parentName);
					this.startElement(parentTag, this.emptyAttributes(), null);
				}
			}
			if (parent.prop.is(ElementProps.FLAG_IGNORE_TEXT)) {
				if (!isWhitespace(text)) {
					return;
				}
			}
			if (parent.prop.is(ElementProps.FLAG_CLOSE_BY_TEXT)) {
				if (!isWhitespace(text)) {
					this.callEndElement(this.fElementStack.pop());
					parent = this.fElementStack.peek();
				}
			}
			if (!this.fSeenBodyElement) {
				if (parent.prop.is(ElementProps.FLAG_HEAD)) {
					this.fDocumentHandler.getDocumentHandler().characters(text, augs);
					return;
				}
				if (parent.prop.code == HTMLElements.HTML || this.inHead()) {
					if (isWhitespace(text)) {
						this.fDocumentHandler.getDocumentHandler().characters(text, augs);
						return;
					}
				}
			}
		}

		// call handler
		final Info parent = this.fElementStack.top >= 1 ? this.fElementStack.peek() : null;
		final XMLDocumentHandler out = parent != null && isTableContext(parent.prop.code) && !isAsciiWhitespace(text)
				? this.fosterOut()
				: contentOut(parent);
		if (out == null) {
			this.fDocumentHandler.characters(text, augs);
		} else {
			out.characters(text, augs);
		}
	} // characters(XMLString,Augmentations)

	/** Ignorable whitespace. */
	public void ignorableWhitespace(XMLString text, Augmentations augs) throws XNIException {
		this.characters(text, augs);
	} // ignorableWhitespace(XMLString,Augmentations)

	/** End element. */
	public void endElement(QName element, final Augmentations augs) throws XNIException {
		// get element information
		ElementProp prop = this.getElementProp(element);

		if (prop.code == HTMLElements.HTML || prop.code == HTMLElements.HEAD) {
			// Do not close the HTML tag until DocumentEnd
			// Do not close the HEAD tag until content starts
			return;
		}
		if (prop.code == HTMLElements.BODY && !this.fDocumentFragment) {
			// </body> closes nothing: what follows it, even after </html>, goes on in the open elements of the body
			// (the HTML Standard's "after body" mode reprocesses it "in body"). Next.js pages stream a second copy
			// of the page there (vercel.com), which used to land outside the body, without its styles (2026-10-09).
			return;
		}

		if (!this.fSeenBodyElement && this.fElementStack.top > 0) {
			// Handle content inside the root element but outside BODY
			Info parent = this.fElementStack.peek();
			if (this.inHead() || (parent.prop.is(ElementProps.FLAG_HEAD) && !parent.prop.is(ElementProps.FLAG_EMPTY))) {
				boolean match = false;
				for (int i = this.fElementStack.top - 1; i >= 0; i--) {
					Info info = this.fElementStack.data[i];
					if (info.prop.code == HTMLElements.HEAD) {
						break;
					}
					if (info.prop.code == prop.code) {
						match = true;
						break;
					}
				}
				if (!match) {
					return;
				}
				for (;;) {
					this.directEndElement(augs);
					if (parent.prop.code == prop.code) {
						break;
					}
					parent = this.fElementStack.peek();
				}
				return;
			}
		}

		this.closeRemovedForms();
		if (prop.code == HTMLElements.FORM) {
			this.endForm();
			return;
		}

		// Find the matching start tag
		int close = 0;
		for (int i = this.fElementStack.top - 1; i >= 0; i--) {
			final Info info = this.fElementStack.data[i];
			if (info.prop.code == HTMLElements.HTML || info.prop.code == HTMLElements.HEAD) {
				// Do not close the HTML tag until DocumentEnd
				// Do not close the HEAD tag until content starts
				break;
			}
			if (info.prop.code == prop.code) {
				// Same tag
				close = this.fElementStack.top - i;
				break;
			}
			if (prop.contains(ElementProps.SET_ALTERNATES)
					&& prop.contains(ElementProps.SET_ALTERNATES, info.prop.code)) {
				// Alternative tag
				close = this.fElementStack.top - i;
				prop = info.prop;
				element = info.qname;
				break;
			}
			if (prop.contains(ElementProps.SET_CLOSE_CLOSES)
					&& prop.contains(ElementProps.SET_CLOSE_CLOSES, info.prop.code)) {
				// Close the parent tag
				continue;
			}
			if (prop.contains(ElementProps.SET_STOP_CLOSE_BY)
					&& prop.contains(ElementProps.SET_STOP_CLOSE_BY, info.prop.code)) {
				// Stop searching
				break;
			}
			if (prop.contains(ElementProps.SET_DIGS_FOR) && prop.contains(ElementProps.SET_DIGS_FOR, info.prop.code)) {
				// Stop searching
				break;
			}
		}

		// If there is no matching start tag
		if (close == 0) {
			if (prop.is(ElementProps.FLAG_END_TO_EMPTY)) {
				// Replace the end tag with an empty tag
				this.startElement(element, null, null);
				if (!prop.is(ElementProps.FLAG_EMPTY)) {
					this.endElement(element, augs);
				}
			}
			return;
		}

		// Ignore the end tag
		Info parent = this.fElementStack.peek();
		if (parent.prop.code != prop.code) {
			if (parent.prop.contains(ElementProps.SET_DISCARDS_CLOSE)
					&& parent.prop.contains(ElementProps.SET_DISCARDS_CLOSE, prop.code)) {
				return;
			}
		}

		// Close tags
		final boolean formattingEnd = isFormattingElement(prop.code);
		List<Info> continueTags = null;
		for (int i = 0; i < close; i++) {
			final Info info = this.fElementStack.pop();
			this.callEndElement(info);
			if (i == close - 1) {
				break;
			}
			if (formattingEnd) {
				// The end tag of a formatting element (b, a, font ...): blocks opened inside it go on after it,
				// as the adoption agency algorithm keeps them (reopen everything, as before).
				// Close the parent tag
				if (prop.contains(ElementProps.SET_CLOSE_CLOSES)
						&& prop.contains(ElementProps.SET_CLOSE_CLOSES, parent.prop.code)) {
					continue;
				}
			} else if (!reopensFormattingAfter(prop.code) || !isFormattingElement(info.prop.code)) {
				// Any other end tag (div, ul, p, li, span ...) pops the elements above its start tag for good
				// (HTML Standard, "in body": "pop elements from the stack of open elements until" the matching
				// element). Only formatting elements come back, the way "reconstruct the active formatting
				// elements" restores them before the next content; table cells, captions, tables, applets,
				// marquees and objects are markers that end that list. Until 2026-10-09 every element was reopened,
				// so after "<div><ul class=x><li>a</div><p>b" the paragraph went into a new ul.x/li (and vanished
				// with a "display: none" on .x, the wordpress.org table of contents).
				continue;
			}
			if (info.removed) {
				// A form a </form> took off the stack: it is not open any more
				continue;
			}
			if (info == this.fFormPointer) {
				// The reopened copy becomes the form element pointer
				this.fFormPointer = null;
			}
			if (continueTags == null) {
				continueTags = new ArrayList<Info>();
			}
			continueTags.add(info);
		}
		if (continueTags != null) {
			for (int i = continueTags.size() - 1; i >= 0; --i) {
				Info info = (Info) continueTags.get(i);
				this.startElement(info.qname, info.atts, null);
			}
		}
	} // endElement(QName,Augmentations)

	// @since Xerces 2.1.0

	/** Sets the document source. */
	public void setDocumentSource(XMLDocumentSource source) {
		fDocumentSource = source;
	} // setDocumentSource(XMLDocumentSource)

	/** Returns the document source. */
	public XMLDocumentSource getDocumentSource() {
		return this.fDocumentSource;
	} // getDocumentSource():XMLDocumentSource

	// removed since Xerces-J 2.3.0

	/** Start document. */
	public void startDocument(XMLLocator locator, String encoding, Augmentations augs) throws XNIException {
		this.startDocument(locator, encoding, null, augs);
	} // startDocument(XMLLocator,String,Augmentations)

	/** Start prefix mapping. */
	public void startPrefixMapping(String prefix, String uri, Augmentations augs) throws XNIException {
		throw new UnsupportedOperationException();
	} // startPrefixMapping(String,String,Augmentations)

	/** End prefix mapping. */
	public void endPrefixMapping(String prefix, Augmentations augs) throws XNIException {
		throw new UnsupportedOperationException();
	} // endPrefixMapping(String,Augmentations)

	//
	// Protected methods
	//

	/** Returns an HTML element. */
	protected ElementProp getElementProp(final QName elementName) {
		String name = elementName.getRawname();
		if (this.fNamespaces && NamespaceBinder.XHTML_1_0_URI.equals(elementName.getUri())) {
			int index = name.indexOf(':');
			if (index != -1) {
				name = name.substring(index + 1);
			}
		}
		HTMLElements.Element element = ElementProps.getHTMLElement(name);
		ElementProp prop = this.fElementProps.getElementProp(element.code);
		return prop;
	}

	/** Call document handler start element. */
	protected final void callStartElement(final QName element, XMLAttributes attrs, final Augmentations augs)
			throws XNIException {
		if (attrs == null) {
			attrs = this.emptyAttributes();
		}
		this.normalizeAttributes(attrs);
		this.fDocumentHandler.startElement(element, attrs, augs);
	} // callStartElement(QName,XMLAttributes,Augmentations)

	/** Call document handler end element. */
	protected final void callEndElement(QName element, Augmentations augs) throws XNIException {
		this.fDocumentHandler.endElement(element, augs);
	} // callEndElement(QName,Augmentations)

	/**
	 * Ends an element popped off the stack where its start tag went. A table's end tag goes into its buffer, which
	 * then sends the whole table after what was taken out of it.
	 */
	private void callEndElement(final Info info) throws XNIException {
		if (info.buffer != null) {
			info.buffer.endElement(info.qname, null);
			info.buffer.close();
		} else if (info.startOut != null) {
			info.startOut.endElement(info.qname, null);
		} else {
			this.callEndElement(info.qname, null);
		}
	}

	/** Where the content of {@code parent} goes, or null for the document handler. */
	private static XMLDocumentHandler contentOut(final Info parent) {
		return parent == null ? null : parent.contentOut;
	}

	/**
	 * Where content taken out of the innermost open table goes: where the table goes, before it (the HTML Standard's
	 * "foster parent"). Null for the document handler.
	 */
	private XMLDocumentHandler fosterOut() {
		for (int i = this.fElementStack.top - 1; i >= 0; --i) {
			final Info info = this.fElementStack.data[i];
			if (info.buffer != null) {
				return info.buffer.parent();
			}
		}
		return null;
	}

	/**
	 * A table, a row group, a row or a column group: the elements in which the HTML Standard's "in table" insertion
	 * modes take other content out of the table (foster parenting).
	 */
	private static boolean isTableContext(final short code) {
		switch (code) {
		case HTMLElements.TABLE:
		case HTMLElements.TBODY:
		case HTMLElements.THEAD:
		case HTMLElements.TFOOT:
		case HTMLElements.TR:
		case HTMLElements.COLGROUP:
			return true;
		default:
			return false;
		}
	}

	/**
	 * Whether an element started in {@code parent} goes before the table instead (foster parenting, 2026-10-09): any
	 * element in a table context but the table's own parts, a table (which closes the open one), style, script,
	 * template, form (left empty in the table) and hidden inputs. Until then they stayed in the table, where Copper made
	 * cells for them (021-FLOAT_IN_TABLE: the floated images between the rows).
	 */
	private static boolean fosters(final Info parent, final ElementProp prop, final XMLAttributes attrs) {
		if (parent == null || !isTableContext(parent.prop.code)) {
			return false;
		}
		switch (prop.code) {
		case HTMLElements.CAPTION:
		case HTMLElements.COL:
		case HTMLElements.COLGROUP:
		case HTMLElements.TBODY:
		case HTMLElements.THEAD:
		case HTMLElements.TFOOT:
		case HTMLElements.TR:
		case HTMLElements.TD:
		case HTMLElements.TH:
		case HTMLElements.TABLE:
		case HTMLElements.STYLE:
		case HTMLElements.SCRIPT:
		case HTMLElements.TEMPLATE:
		case HTMLElements.FORM:
			return false;
		case HTMLElements.INPUT:
			return attrs == null || !"hidden".equalsIgnoreCase(attrs.getValue("type"));
		default:
			return true;
		}
	}

	protected final void directStartElement(final ElementProp prop, final QName element, XMLAttributes attrs,
			final Augmentations augs) throws XNIException {
		XMLDocumentHandler handler = this.fDocumentHandler.getDocumentHandler();
		if (attrs == null) {
			attrs = this.emptyAttributes();
		}
		this.normalizeAttributes(attrs);
		if (!prop.is(ElementProps.FLAG_EMPTY)) {
			this.fElementStack.push(new Info(prop, element, null));
			handler.startElement(element, attrs, augs);
		} else {
			handler.emptyElement(element, attrs, augs);
		}
		return;
	}

	private void normalizeAttributes(XMLAttributes attrs) {
		if (attrs == null) {
			return;
		}
		for (int i = 0; i < attrs.getLength(); ++i) {
			QName name = attrs.getName(i);
			String prefix = name.getPrefix();
			if (prefix == null) {
				prefix = "";
			}
			String localpart = name.getLocalpart();
			if (localpart == null) {
				localpart = "";
			}
			String rawname = name.getRawname();
			if (rawname == null) {
				rawname = localpart;
			}
			String uri = name.getUri();
			if (uri == null) {
				uri = "";
			}
			name.setValues(prefix, localpart, rawname, uri);
			attrs.setName(i, name);
		}
	}

	protected final void directEndElement(Augmentations augs) throws XNIException {
		final Info info = this.fElementStack.pop();
		final XMLDocumentHandler handler = this.fDocumentHandler.getDocumentHandler();
		handler.endElement(info.qname, augs);
		return;
	}

	/**
	 * Formatting elements of the HTML Standard ("a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small",
	 * "strike", "strong", "tt", "u"): the elements the list of active formatting elements carries over block
	 * boundaries.
	 */
	protected static boolean isFormattingElement(final short code) {
		switch (code) {
		case HTMLElements.A:
		case HTMLElements.B:
		case HTMLElements.BIG:
		case HTMLElements.CODE:
		case HTMLElements.EM:
		case HTMLElements.FONT:
		case HTMLElements.I:
		case HTMLElements.NOBR:
		case HTMLElements.S:
		case HTMLElements.SMALL:
		case HTMLElements.STRIKE:
		case HTMLElements.STRONG:
		case HTMLElements.TT:
		case HTMLElements.U:
			return true;
		default:
			return false;
		}
	}

	/**
	 * False for the end tags that clear the list of active formatting elements up to the last marker (td, th,
	 * caption, table, applet, marquee, object): formatting elements opened inside them do not come back after them.
	 */
	protected static boolean reopensFormattingAfter(final short code) {
		switch (code) {
		case HTMLElements.TD:
		case HTMLElements.TH:
		case HTMLElements.CAPTION:
		case HTMLElements.TABLE:
		case HTMLElements.APPLET:
		case HTMLElements.MARQUEE:
		case HTMLElements.OBJECT:
			return false;
		default:
			return true;
		}
	}

	/**
	 * A form end tag, as the HTML Standard's "in body" mode handles it (2026-10-09): it clears the form element
	 * pointer and, when that form is in scope, pops the elements with implied end tags (p, li, option ...) and takes
	 * the form off the stack. Elements still open inside it stay open, and what follows goes into them, inside the
	 * form (un.org: the "A-Z Site Index" after "&lt;form&gt;&lt;div&gt;&lt;div&gt;...&lt;/form&gt;"). The form
	 * stays on this stack, marked removed, until they are closed ({@link #closeRemovedForms()}).
	 */
	private void endForm() {
		final Info node = this.fFormPointer;
		this.fFormPointer = null;
		if (node == null) {
			return;
		}
		int index = -1;
		for (int i = this.fElementStack.top - 1; i >= 0; --i) {
			final Info info = this.fElementStack.data[i];
			if (info == node) {
				index = i;
				break;
			}
			if (isScopeMarker(info.prop.code)) {
				return;
			}
		}
		if (index < 0) {
			return;
		}
		while (this.fElementStack.top - 1 > index && hasImpliedEndTag(this.fElementStack.peek().prop.code)) {
			this.callEndElement(this.fElementStack.pop());
		}
		if (this.fElementStack.top - 1 == index) {
			this.callEndElement(this.fElementStack.pop());
		} else {
			node.removed = true;
		}
	}

	/**
	 * Closes the forms a form end tag took off the stack once the elements left open inside them are closed, before
	 * the next content goes in.
	 */
	private void closeRemovedForms() {
		while (this.fElementStack.top > 0 && this.fElementStack.peek().removed) {
			this.callEndElement(this.fElementStack.pop());
		}
	}

	/** "Close a p element" when one is in button scope: pops it and every element opened inside it. */
	private void closeParagraphInButtonScope() {
		for (int i = this.fElementStack.top - 1; i >= 0; --i) {
			final short code = this.fElementStack.data[i].prop.code;
			if (code == HTMLElements.P) {
				while (this.fElementStack.top > i) {
					final Info info = this.fElementStack.pop();
					this.callEndElement(info);
				}
				return;
			}
			if (code == HTMLElements.BUTTON || isScopeMarker(code)) {
				return;
			}
		}
	}

	/** The elements that end the HTML Standard's default scope ("has an element in scope"). */
	private static boolean isScopeMarker(final short code) {
		switch (code) {
		case HTMLElements.APPLET:
		case HTMLElements.CAPTION:
		case HTMLElements.HTML:
		case HTMLElements.TABLE:
		case HTMLElements.TD:
		case HTMLElements.TH:
		case HTMLElements.MARQUEE:
		case HTMLElements.OBJECT:
		case HTMLElements.TEMPLATE:
			return true;
		default:
			return false;
		}
	}

	/** The elements "generate implied end tags" pops. */
	private static boolean hasImpliedEndTag(final short code) {
		switch (code) {
		case HTMLElements.DD:
		case HTMLElements.DT:
		case HTMLElements.LI:
		case HTMLElements.OPTGROUP:
		case HTMLElements.OPTION:
		case HTMLElements.P:
		case HTMLElements.RB:
		case HTMLElements.RP:
		case HTMLElements.RT:
		case HTMLElements.RTC:
			return true;
		default:
			return false;
		}
	}

	/**
	 * Public identifiers that put a document in quirks mode (HTML Standard, "The initial insertion mode"), lower
	 * case; the ones that start with these.
	 */
	private static final String[] QUIRKS_PUBLIC_PREFIXES = { "+//silmaril//dtd html pro v0r11 19970101//",
			"-//as//dtd html 3.0 aswedit + extensions//", "-//advasoft ltd//dtd html 3.0 aswedit + extensions//",
			"-//ietf//dtd html 2.0 level 1//", "-//ietf//dtd html 2.0 level 2//", "-//ietf//dtd html 2.0 strict level 1//",
			"-//ietf//dtd html 2.0 strict level 2//", "-//ietf//dtd html 2.0 strict//", "-//ietf//dtd html 2.0//",
			"-//ietf//dtd html 2.1e//", "-//ietf//dtd html 3.0//", "-//ietf//dtd html 3.2 final//",
			"-//ietf//dtd html 3.2//", "-//ietf//dtd html 3//", "-//ietf//dtd html level 0//",
			"-//ietf//dtd html level 1//", "-//ietf//dtd html level 2//", "-//ietf//dtd html level 3//",
			"-//ietf//dtd html strict level 0//", "-//ietf//dtd html strict level 1//",
			"-//ietf//dtd html strict level 2//", "-//ietf//dtd html strict level 3//", "-//ietf//dtd html strict//",
			"-//ietf//dtd html//", "-//metrius//dtd metrius presentational//",
			"-//microsoft//dtd internet explorer 2.0 html strict//", "-//microsoft//dtd internet explorer 2.0 html//",
			"-//microsoft//dtd internet explorer 2.0 tables//", "-//microsoft//dtd internet explorer 3.0 html strict//",
			"-//microsoft//dtd internet explorer 3.0 html//", "-//microsoft//dtd internet explorer 3.0 tables//",
			"-//netscape comm. corp.//dtd html//", "-//netscape comm. corp.//dtd strict html//",
			"-//o'reilly and associates//dtd html 2.0//", "-//o'reilly and associates//dtd html extended 1.0//",
			"-//o'reilly and associates//dtd html extended relaxed 1.0//",
			"-//sq//dtd html 2.0 hotmetal + extensions//",
			"-//softquad software//dtd hotmetal pro 6.0::19990601::extensions to html 4.0//",
			"-//softquad//dtd hotmetal pro 4.0::19971010::extensions to html 4.0//",
			"-//spyglass//dtd html 2.0 extended//", "-//sun microsystems corp.//dtd hotjava html//",
			"-//sun microsystems corp.//dtd hotjava strict html//", "-//w3c//dtd html 3 1995-03-24//",
			"-//w3c//dtd html 3.2 draft//", "-//w3c//dtd html 3.2 final//", "-//w3c//dtd html 3.2//",
			"-//w3c//dtd html 3.2s draft//", "-//w3c//dtd html 4.0 frameset//", "-//w3c//dtd html 4.0 transitional//",
			"-//w3c//dtd html experimental 19960712//", "-//w3c//dtd html experimental 970421//",
			"-//w3c//dtd w3 html//", "-//w3o//dtd w3 html 3.0//", "-//webtechs//dtd mozilla html 2.0//",
			"-//webtechs//dtd mozilla html//" };

	/** Whether a doctype puts the document in quirks mode (HTML Standard, "The initial insertion mode"). */
	private static boolean isQuirksDoctype(final String name, final String publicId, final String systemId) {
		if (name == null || !name.equalsIgnoreCase("html")) {
			return true;
		}
		final String pub = publicId == null ? null : publicId.toLowerCase(java.util.Locale.ROOT);
		final String sys = systemId == null ? null : systemId.toLowerCase(java.util.Locale.ROOT);
		if ("http://www.ibm.com/data/dtd/v11/ibmxhtml1-transitional.dtd".equals(sys)) {
			return true;
		}
		if (pub == null) {
			return false;
		}
		if (pub.equals("-//w3o//dtd w3 html strict 3.0//en//") || pub.equals("-/w3c/dtd html 4.0 transitional/en")
				|| pub.equals("html")) {
			return true;
		}
		for (final String prefix : QUIRKS_PUBLIC_PREFIXES) {
			if (pub.startsWith(prefix)) {
				return true;
			}
		}
		return sys == null && (pub.startsWith("-//w3c//dtd html 4.01 frameset//")
				|| pub.startsWith("-//w3c//dtd html 4.01 transitional//"));
	}

	/** Returns a set of empty attributes. */
	protected final XMLAttributes emptyAttributes() {
		this.fEmptyAttrs.removeAllAttributes();
		return this.fEmptyAttrs;
	} // emptyAttributes():XMLAttributes

	//
	// Protected static methods
	//

	/** Modifies the given name based on the specified mode. */
	protected static final String modifyName(String name, short mode) {
		switch (mode) {
		case NAMES_UPPERCASE:
			return name.toUpperCase();
		case NAMES_LOWERCASE:
			return name.toLowerCase();
		}
		return name;
	} // modifyName(String,short):String

	/**
	 * Converts HTML names string value to constant value.
	 * 
	 * @see #NAMES_NO_CHANGE
	 * @see #NAMES_LOWERCASE
	 * @see #NAMES_UPPERCASE
	 */
	protected static final short getNamesValue(String value) {
		if (value.equals("lower")) {
			return NAMES_LOWERCASE;
		}
		if (value.equals("upper")) {
			return NAMES_UPPERCASE;
		}
		return NAMES_NO_CHANGE;
	} // getNamesValue(String):short

	/**
	 * Only ASCII white space stays in a table: the HTML Standard takes text with anything else out of it, also a
	 * no-break or an ideographic space.
	 */
	private static boolean isAsciiWhitespace(final XMLString text) {
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			// tab, line feed, form feed, carriage return, space
			if (c != 0x09 && c != 0x0A && c != 0x0C && c != 0x0D && c != 0x20) {
				return false;
			}
		}
		return true;
	}

	protected static boolean isWhitespace(final XMLString text) {
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private class RecoderFilter extends DefaultFilter {
		public void comment(XMLString text, Augmentations augs) throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.comment(text, augs);
				return;
			}
			super.comment(text, augs);
		}

		public void processingInstruction(String target, XMLString data, Augmentations augs) throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.processingInstruction(target, data, augs);
				return;
			}
			super.processingInstruction(target, data, augs);
		}

		public void startCDATA(Augmentations augs) throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.startCDATA(augs);
				return;
			}
			super.startCDATA(augs);
		}

		public void endCDATA(Augmentations augs) throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.endCDATA(augs);
				return;
			}
			super.endCDATA(augs);
		}

		public void emptyElement(final QName element, XMLAttributes attrs, final Augmentations augs)
				throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.emptyElement(element, attrs, augs);
				return;
			}
			if (attrs == null) {
				attrs = emptyAttributes();
			}
			super.emptyElement(element, attrs, augs);
		}

		public void startElement(final QName element, XMLAttributes attrs, final Augmentations augs)
				throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.startElement(element, attrs, augs);
				return;
			}
			if (attrs == null) {
				attrs = emptyAttributes();
			}
			super.startElement(element, attrs, augs);
		}

		public void characters(final XMLString text, final Augmentations augs) throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.characters(text, augs);
				return;
			}
			super.characters(text, augs);
		}

		public void endElement(QName element, Augmentations augs) throws XNIException {
			if (fRecorder.isRecording()) {
				fRecorder.endElement(element, augs);
				return;
			}
			super.endElement(element, augs);
		}

	}

} // class HTMLTagBalancer


