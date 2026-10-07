package net.zamasoft.balancer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import javax.xml.parsers.SAXParserFactory;
import org.htmlunit.cyberneko.HTMLElements;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Information for balancing elements.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: ElementProps.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class ElementProps {
	private static final HTMLElements HTML_ELEMENTS = new HTMLElements();
	private static final Map<Short, String> ELEMENT_NAMES = new HashMap<Short, String>();

	static HTMLElements.Element getHTMLElement(String name) {
		HTMLElements.Element element = HTML_ELEMENTS.getElement(name);
		ELEMENT_NAMES.put(Short.valueOf(element.code), element.name);
		return element;
	}

	static String getHTMLElementName(short code) {
		return ELEMENT_NAMES.get(Short.valueOf(code));
	}

	/**
	 * Document content (BODY).
	 */
	public static final int FLAG_BODY = 0x00000001;

	/**
	 * Head content (LINK, META, LINK, etc.).
	 */
	public static final int FLAG_HEAD = 0x00000002;

	/**
	 * An empty tag (BR, HR, COL, etc.).
	 */
	public static final int FLAG_EMPTY = 0x00000004;

	/**
	 * Converts an end tag to an empty tag (BR, P).
	 */
	public static final int FLAG_END_TO_EMPTY = 0x00000008;

	/**
	 * Ignores text directly inside this element.
	 */
	public static final int FLAG_IGNORE_TEXT = 0x00000010;

	/**
	 * Closes when text occurs.
	 */
	public static final int FLAG_CLOSE_BY_TEXT = 0x00000020;

	/**
	 * Alternative elements (such as TH for TD). An end tag for an alternative to this element is replaced
	 * with an end tag for this element.
	 */
	public static final int SET_ALTERNATES = 0;

	/**
	 * Unwinds the ancestor stack until the specified element is found. Does not unwind if the element is absent.
	 */
	public static final int SET_DIGS_FOR = 1;

	/**
	 * Elements automatically inserted as parents of this element (such as TR for TD).
	 * No element is inserted if the parent is one of the specified elements.
	 */
	public static final int SET_INSERT_PARENTS = 2;

	/**
	 * Ancestor elements that this element's start tag closes. The ancestor stack is unwound to the specified element.
	 */
	public static final int SET_OPEN_CLOSES = 3;

	/**
	 * Elements that do not resume when this element's end tag closes them. When tags do not match, the end tag
	 * closes and reopens parent elements, but these elements do not reopen.
	 */
	public static final int SET_CLOSE_CLOSES = 4;

	/**
	 * Elements whose start tags are ignored directly inside this element.
	 */
	public static final int SET_DISCARDS_OPEN = 5;

	/**
	 * Elements whose end tags are ignored directly inside this element.
	 */
	public static final int SET_DISCARDS_CLOSE = 6;

	/**
	 * Parent elements that close at this element's start tag and reopen immediately after it.
	 */
	public static final int SET_OPEN_SPLITS = 7;

	/**
	 * Ancestor elements that stop the unwinding performed by SET_OPEN_CLOSES.
	 */
	public static final int SET_STOP_CLOSE_BY = 8;

	/**
	 * Elements to insert when text occurs.
	 */
	public static final int SET_INSERT_BY_TEXT = 9;

	public static class ElementProp {
		/**
		 * The element code.
		 */
		public final short code;

		/**
		 * Flags.
		 */
		public final int flags;

		public final short[][] tags = new short[SET_INSERT_BY_TEXT + 1][];

		public ElementProp(short code, int flags) {
			this.code = code;
			this.flags = flags;
		}

		public boolean is(int flag) {
			return (this.flags & flag) != 0;
		}

		public boolean contains(int set) {
			return this.tags[set] != null;
		}

		public boolean contains(int set, short code) {
			short[] tags = this.tags[set];
			for (int i = 0; i < tags.length; ++i) {
				if (tags[i] == code) {
					return true;
				}
			}
			return false;
		}
	}

	private static final Map<String, ElementProps> CONFIGS = new HashMap<String, ElementProps>();

	public static final ElementProps getElementProps(String name) {
		try {
			ElementProps config = (ElementProps) CONFIGS.get(name);
			if (config == null) {
				try (InputStream in = ElementProps.class.getResourceAsStream(name)) {
					config = new ElementProps(new InputSource(in));
				}
				CONFIGS.put(name, config);
			}
			return config;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException();
		}
	}

	private final ElementProp DEFAULT_ELEMENT_PROP;
	private final ElementProp[] PROPS = new ElementProp[150];

	private ElementProps(InputSource is) throws Exception {
		SAXParserFactory factory = SAXParserFactory.newInstance();
		factory.setNamespaceAware(false);
		javax.xml.parsers.SAXParser parser = factory.newSAXParser();
		DefaultHandler handler = new DefaultHandler() {
			ElementProp elem;
			StringBuffer buff = null;
			String tagsetName;
			Map<String, List<String>> tagsets = new HashMap<String, List<String>>();

			public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
				if (qName.equals("tagset")) {
					this.tagsetName = atts.getValue("name");
				} else if (qName.equals("tag")) {
					HTMLElements.Element e = getHTMLElement(atts.getValue("name"));
					int flags = 0;
					String flagStr = atts.getValue("flags");
					if (flagStr != null) {
						for (StringTokenizer st = new StringTokenizer(flagStr, "|"); st.hasMoreTokens();) {
							String flag = st.nextToken().trim();
							if (flag.length() == 0) {
								continue;
							}
							if (flag.equals("BODY")) {
								flags |= FLAG_BODY;
							} else if (flag.equals("HEAD")) {
								flags |= FLAG_HEAD;
							} else if (flag.equals("EMPTY")) {
								flags |= FLAG_EMPTY;
							} else if (flag.equals("END_TO_EMPTY")) {
								flags |= FLAG_END_TO_EMPTY;
							} else if (flag.equals("IGNORE_TEXT")) {
								flags |= FLAG_IGNORE_TEXT;
							} else if (flag.equals("CLOSE_BY_TEXT")) {
								flags |= FLAG_CLOSE_BY_TEXT;
							} else {
								throw new SAXException("Unexpected flag: " + flag);
							}
						}
					}
					this.elem = new ElementProp(e.code, flags);
					PROPS[e.code] = this.elem;
				}
				this.buff = null;
			}

			public void characters(char[] ch, int off, int len) throws SAXException {
				if (this.buff == null) {
					this.buff = new StringBuffer();
				}
				this.buff.append(ch, off, len);
			}

			public void endElement(String uri, String localName, String qName) throws SAXException {
				if (this.buff == null) {
					return;
				}
				List<String> list = new ArrayList<String>();
				for (StringTokenizer st = new StringTokenizer(this.buff.toString(), ","); st.hasMoreTokens();) {
					String str = st.nextToken().trim();
					if (str.length() == 0) {
						continue;
					}
					if (str.startsWith("-")) {
						// Exclude
						str = str.substring(1);
						list.remove(str);
					} else if (str.startsWith("$")) {
						// Tag set
						str = str.substring(1);
						List<String> tagset = this.tagsets.get(str);
						if (tagset != null) {
							list.addAll(tagset);
						}
					} else {
						// Tag
						list.add(str);
					}
				}
				this.buff = null;
				if (!list.isEmpty()) {
					short[] codes = new short[list.size()];
					for (int i = 0; i < list.size(); ++i) {
						HTMLElements.Element close = getHTMLElement((String) list.get(i));
						codes[i] = close.code;
					}
					if (qName.equals("tagset")) {
						this.tagsets.put(this.tagsetName, list);
					} else if (qName.equals("alternates")) {
						this.elem.tags[SET_ALTERNATES] = codes;
					} else if (qName.equals("digsFor")) {
						this.elem.tags[SET_DIGS_FOR] = codes;
					} else if (qName.equals("insertParents")) {
						this.elem.tags[SET_INSERT_PARENTS] = codes;
					} else if (qName.equals("openCloses")) {
						this.elem.tags[SET_OPEN_CLOSES] = codes;
					} else if (qName.equals("closeCloses")) {
						this.elem.tags[SET_CLOSE_CLOSES] = codes;
					} else if (qName.equals("discardsOpen")) {
						this.elem.tags[SET_DISCARDS_OPEN] = codes;
					} else if (qName.equals("discardsClose")) {
						this.elem.tags[SET_DISCARDS_CLOSE] = codes;
					} else if (qName.equals("openSplits")) {
						this.elem.tags[SET_OPEN_SPLITS] = codes;
					} else if (qName.equals("stopCloseBy")) {
						this.elem.tags[SET_STOP_CLOSE_BY] = codes;
					} else if (qName.equals("insertByText")) {
						this.elem.tags[SET_INSERT_BY_TEXT] = codes;
					}
				}
			}
		};
		parser.parse(is, handler);
		DEFAULT_ELEMENT_PROP = PROPS[HTMLElements.UNKNOWN];
	}

	public ElementProp getElementProp(short code) {
		if (code >= PROPS.length) {
			return DEFAULT_ELEMENT_PROP;
		}
		ElementProp element = PROPS[code];
		if (element == null) {
			return DEFAULT_ELEMENT_PROP;
		}
		return element;
	}
}


