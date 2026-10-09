package net.zamasoft.balancer;

import java.util.ArrayList;
import java.util.List;

import org.htmlunit.cyberneko.xerces.util.XMLAttributesImpl;
import org.htmlunit.cyberneko.xerces.xni.Augmentations;
import org.htmlunit.cyberneko.xerces.xni.NamespaceContext;
import org.htmlunit.cyberneko.xerces.xni.QName;
import org.htmlunit.cyberneko.xerces.xni.XMLAttributes;
import org.htmlunit.cyberneko.xerces.xni.XMLDocumentHandler;
import org.htmlunit.cyberneko.xerces.xni.XMLLocator;
import org.htmlunit.cyberneko.xerces.xni.XMLString;
import org.htmlunit.cyberneko.xerces.xni.XNIException;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLDocumentSource;

/**
 * Holds back the events of an open table, so that what the HTML Standard takes out of the table (foster parenting:
 * elements and text directly in a table, a row group or a row) can go to the parent before it (2026-10-09).
 *
 * <p>
 * The table starts with its own start tag; {@link #close()} sends everything to the parent at the table's end. A
 * table larger than {@link #LIMIT} characters is sent as it stands and the rest streams: content to take out of it
 * after that stays where it is. Each event is sent with the source position it was read at ({@link HeldLocator}).
 * </p>
 */
final class TableBuffer implements XMLDocumentHandler {
	/** Characters (names, attribute values, text) held back at most, per table. */
	static final long LIMIT = 1 << 20;

	private interface Send {
		void send(XMLDocumentHandler handler);
	}

	private record Event(Send send, HeldLocator.Position position) {
	}

	private final XMLDocumentHandler parent;

	private final HeldLocator locator;

	private List<Event> events = new ArrayList<>();

	private long size;

	TableBuffer(final XMLDocumentHandler parent, final HeldLocator locator) {
		this.parent = parent;
		this.locator = locator;
	}

	/** Where the table goes, and what is taken out of it before it. */
	XMLDocumentHandler parent() {
		return this.parent;
	}

	/** Sends what is held to the parent; the events after it go straight through. */
	void close() {
		final List<Event> events = this.events;
		if (events == null) {
			return;
		}
		this.events = null;
		final HeldLocator.Position outer = this.locator == null ? null : this.locator.held();
		try {
			for (final Event event : events) {
				if (this.locator != null) {
					this.locator.hold(event.position());
				}
				event.send().send(this.parent);
			}
		} finally {
			if (this.locator != null) {
				this.locator.hold(outer);
			}
		}
	}

	private void add(final Send send, final long chars) {
		if (this.events == null) {
			send.send(this.parent);
			return;
		}
		this.events.add(new Event(send, this.locator == null ? null : this.locator.position()));
		this.size += chars;
		if (this.size > LIMIT) {
			this.close();
		}
	}

	private static XMLString copy(final XMLString text) {
		return text == null ? null : new XMLString(text.toString());
	}

	private static Augmentations copy(final Augmentations augs) {
		return augs == null ? null : new AugmentationsImpl(augs);
	}

	private static XMLAttributes copy(final XMLAttributes attributes) {
		if (attributes == null) {
			return null;
		}
		final XMLAttributesImpl copy = new XMLAttributesImpl();
		final QName name = new QName();
		for (int i = 0; i < attributes.getLength(); i++) {
			attributes.getName(i, name);
			copy.addAttribute(new QName(name), attributes.getType(i), attributes.getValue(i),
					attributes.getNonNormalizedValue(i), attributes.isSpecified(i));
		}
		return copy;
	}

	private static long chars(final XMLAttributes attributes) {
		long chars = 0;
		if (attributes != null) {
			for (int i = 0; i < attributes.getLength(); i++) {
				final String value = attributes.getValue(i);
				final String name = attributes.getName(i).getRawname();
				chars += (name == null ? 0 : name.length()) + (value == null ? 0 : value.length());
			}
		}
		return chars;
	}

	@Override
	public void startElement(final QName element, final XMLAttributes attributes, final Augmentations augs)
			throws XNIException {
		final QName e = new QName(element);
		final XMLAttributes a = copy(attributes);
		final Augmentations g = copy(augs);
		this.add(h -> h.startElement(e, a, g), element.getRawname().length() + chars(attributes));
	}

	@Override
	public void emptyElement(final QName element, final XMLAttributes attributes, final Augmentations augs)
			throws XNIException {
		final QName e = new QName(element);
		final XMLAttributes a = copy(attributes);
		final Augmentations g = copy(augs);
		this.add(h -> h.emptyElement(e, a, g), element.getRawname().length() + chars(attributes));
	}

	@Override
	public void endElement(final QName element, final Augmentations augs) throws XNIException {
		final QName e = new QName(element);
		final Augmentations g = copy(augs);
		this.add(h -> h.endElement(e, g), element.getRawname().length());
	}

	@Override
	public void characters(final XMLString text, final Augmentations augs) throws XNIException {
		final XMLString t = copy(text);
		final Augmentations g = copy(augs);
		this.add(h -> h.characters(t, g), text.length());
	}

	@Override
	public void comment(final XMLString text, final Augmentations augs) throws XNIException {
		final XMLString t = copy(text);
		final Augmentations g = copy(augs);
		this.add(h -> h.comment(t, g), text.length());
	}

	@Override
	public void processingInstruction(final String target, final XMLString data, final Augmentations augs)
			throws XNIException {
		final XMLString d = copy(data);
		final Augmentations g = copy(augs);
		this.add(h -> h.processingInstruction(target, d, g), target.length() + (data == null ? 0 : data.length()));
	}

	@Override
	public void startCDATA(final Augmentations augs) throws XNIException {
		final Augmentations g = copy(augs);
		this.add(h -> h.startCDATA(g), 0);
	}

	@Override
	public void endCDATA(final Augmentations augs) throws XNIException {
		final Augmentations g = copy(augs);
		this.add(h -> h.endCDATA(g), 0);
	}

	// Document-level events do not occur inside a table: they go straight to the parent

	@Override
	public void startDocument(final XMLLocator locator, final String encoding, final NamespaceContext namespaceContext,
			final Augmentations augs) throws XNIException {
		this.parent.startDocument(locator, encoding, namespaceContext, augs);
	}

	@Override
	public void xmlDecl(final String version, final String encoding, final String standalone, final Augmentations augs)
			throws XNIException {
		this.parent.xmlDecl(version, encoding, standalone, augs);
	}

	@Override
	public void doctypeDecl(final String rootElement, final String publicId, final String systemId,
			final Augmentations augs) throws XNIException {
		this.parent.doctypeDecl(rootElement, publicId, systemId, augs);
	}

	@Override
	public void endDocument(final Augmentations augs) throws XNIException {
		this.parent.endDocument(augs);
	}

	@Override
	public void setDocumentSource(final XMLDocumentSource source) {
		this.parent.setDocumentSource(source);
	}

	@Override
	public XMLDocumentSource getDocumentSource() {
		return this.parent.getDocumentSource();
	}
}
