package net.zamasoft.balancer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringReader;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The trees {@link TagBalancer} builds for end tags that close elements left open, compared with Chrome 151's
 * {@code document.body.innerHTML} for the same body (2026-10-09).
 *
 * <p>
 * The HTML Standard ("in body" insertion mode) pops every element above the matching start tag for good; only
 * formatting elements (a, b, i, font ...) come back, through "reconstruct the active formatting elements". Until
 * 2026-10-09 the balancer reopened every popped element, so the text after {@code </div>} went into a copy of an
 * unclosed {@code ul}/{@code li} (and disappeared with a {@code display: none} on the list: the table of contents of
 * wordpress.org).
 * </p>
 *
 * <p>
 * Also table rows whose cells are not closed (one {@code tbody} per row until 2026-10-09: html-entities, textfiles,
 * whatwg-tables) and options inside an {@code optgroup} (they used to close the group: rails-guides), the form end
 * tag and the form element pointer (un.org, lwn.net), tables that close a p outside quirks mode, implied colgroups
 * (w3.org), the options of a datalist, content after the body end tag, and form controls and labels inside a
 * button (pmc.ncbi.nlm.nih.gov).
 * Each case is checked with legacy.xml, with html4.xml, and with legacy.xml switched to html4.xml at the body start
 * tag, as foliojet does for documents in standards mode.
 * </p>
 */
class TagBalancerTest {
	/** Elements innerHTML writes without an end tag. */
	private static final java.util.Set<String> VOID = java.util.Set.of("area", "base", "br", "col", "embed", "hr",
			"img", "input", "link", "meta", "param", "source", "track", "wbr");

	/**
	 * Serializes the body content with the nesting rules of {@code config} (legacy.xml, or html4.xml that foliojet
	 * switches to in standards mode): lower-case names, attributes in source order, text as is. "legacy.xml&gt;html4.xml"
	 * switches at the body start tag, as foliojet's ForeignContentFilter does.
	 */
	private static String body(final String doctype, final String html, final String config) throws Exception {
		final StringBuilder out = new StringBuilder();
		final boolean[] inBody = { false };
		final SAXParser parser = new SAXParser();
		final TagBalancer balancer = new TagBalancer();
		final String[] configs = config.split(">");
		balancer.setElementProps(ElementProps.getElementProps(configs[0]));
		final org.htmlunit.cyberneko.filters.DefaultFilter switcher = new org.htmlunit.cyberneko.filters.DefaultFilter() {
			@Override
			public void startElement(final org.htmlunit.cyberneko.xerces.xni.QName element,
					final org.htmlunit.cyberneko.xerces.xni.XMLAttributes atts,
					final org.htmlunit.cyberneko.xerces.xni.Augmentations augs) {
				super.startElement(element, atts, augs);
				if (configs.length > 1 && element.getLocalpart().equalsIgnoreCase("body")) {
					balancer.setElementProps(ElementProps.getElementProps(configs[1]));
				}
			}
		};
		parser.setProperty("http://cyberneko.org/html/properties/filters",
				new org.htmlunit.cyberneko.xerces.xni.parser.XMLDocumentFilter[] { balancer, switcher });
		parser.setContentHandler(new DefaultHandler() {
			@Override
			public void startElement(final String uri, final String local, final String qName, final Attributes atts) {
				final String name = qName.toLowerCase();
				if (name.equals("body")) {
					inBody[0] = true;
				} else if (inBody[0]) {
					out.append('<').append(name);
					for (int i = 0; i < atts.getLength(); i++) {
						out.append(' ').append(atts.getQName(i).toLowerCase()).append("=\"").append(atts.getValue(i))
								.append('"');
					}
					out.append('>');
				}
			}

			@Override
			public void endElement(final String uri, final String local, final String qName) {
				final String name = qName.toLowerCase();
				if (name.equals("body")) {
					inBody[0] = false;
				} else if (inBody[0] && !VOID.contains(name)) {
					out.append("</").append(name).append('>');
				}
			}

			@Override
			public void characters(final char[] ch, final int start, final int length) {
				if (inBody[0]) {
					out.append(ch, start, length);
				}
			}
		});
		parser.parse(new InputSource(
				new StringReader(doctype + "<html><head></head><body>" + html + "</body></html>")));
		return out.toString();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			# Block end tags do not reopen the blocks and inline elements they close
			<div class="a"><ul class="x"><li>one</div><p>AFTER</p> | <div class="a"><ul class="x"><li>one</li></ul></div><p>AFTER</p>
			<div><ul><li>one<ul><li>two</ul></div><p>after</p> | <div><ul><li>one<ul><li>two</li></ul></li></ul></div><p>after</p>
			<div><ol><li>a<li>b</div>c | <div><ol><li>a</li><li>b</li></ol></div>c
			<p><span class="r">x</p>y | <p><span class="r">x</span></p>y
			<section><div><span>x</section>y | <section><div><span>x</span></div></section>y
			# Formatting elements come back after a block end tag
			<div><b>bold</div>tail | <div><b>bold</b></div><b>tail</b>
			<div><a href="#">x</div>y | <div><a href="#">x</a></div><a href="#">y</a>
			<li><div><i>x</li>y | <li><div><i>x</i></div></li><i>y</i>
			# ... but not after a table cell (a marker in the list of active formatting elements)
			<table><tr><td><b>x</td><td>y</td></tr></table>z | <table><tbody><tr><td><b>x</b></td><td>y</td></tr></tbody></table>z
			# The start tags of HTML5 block elements close an open p ("close a p element")
			<p>a<section>b</section>c</p> | <p>a</p><section>b</section>c<p></p>
			<p><span>x<nav>y</nav>z | <p><span>x</span></p><nav>y</nav>z
			<div><p>a<figure>f</figure>b</div> | <div><p>a</p><figure>f</figure>b</div>
			<p>a<article>b<header>h</header></article> | <p>a</p><article>b<header>h</header></article>
			<button><p>a<section>b</section></button> | <button><p>a</p><section>b</section></button>
			# A tr in an open cell closes the cell and the row, not the row group ("in cell" mode; one tbody per row until 2026-10-09)
			<table><tr><td>a<tr><td>b</table> | <table><tbody><tr><td>a</td></tr><tr><td>b</td></tr></tbody></table>
			<table><tbody><tr><td>a <span>x</span> <tr><td>b</table> | <table><tbody><tr><td>a <span>x</span> </td></tr><tr><td>b</td></tr></tbody></table>
			<table><thead><tr><th>N<th>C<tbody><tr id=a><td><code>x</code><td><span>A</span> <tr id=b><td>y</table> | <table><thead><tr><th>N</th><th>C</th></tr></thead><tbody><tr id="a"><td><code>x</code></td><td><span>A</span> </td></tr><tr id="b"><td>y</td></tr></tbody></table>
			<table><tr><th>a<td>b<tr><th>c</table> | <table><tbody><tr><th>a</th><td>b</td></tr><tr><th>c</th></tr></tbody></table>
			<table><tr><td><div>a<tr><td>b</table> | <table><tbody><tr><td><div>a</div></td></tr><tr><td>b</td></tr></tbody></table>
			<table><tr><td><b>x<tr><td>y</table>z | <table><tbody><tr><td><b>x</b></td></tr><tr><td>y</td></tr></tbody></table>z
			<table><thead><tr><th>h<tbody><tr><td>a<tr><td>b<tfoot><tr><td>f</table> | <table><thead><tr><th>h</th></tr></thead><tbody><tr><td>a</td></tr><tr><td>b</td></tr></tbody><tfoot><tr><td>f</td></tr></tfoot></table>
			<table><tr><td><table><tr><td>x<tr><td>y</table><tr><td>z</table> | <table><tbody><tr><td><table><tbody><tr><td>x</td></tr><tr><td>y</td></tr></tbody></table></td></tr><tr><td>z</td></tr></tbody></table>
			# Options go inside an optgroup; an optgroup closes an open option and optgroup ("in select" mode)
			<select><option>i</option><optgroup label=g><option>a</option><option>b</option></optgroup><optgroup label=h><option>c</option></optgroup></select> | <select><option>i</option><optgroup label="g"><option>a</option><option>b</option></optgroup><optgroup label="h"><option>c</option></optgroup></select>
			<select><optgroup label=g><option>a<option>b<optgroup label=h><option>c</select> | <select><optgroup label="g"><option>a</option><option>b</option></optgroup><optgroup label="h"><option>c</option></optgroup></select>
			<select><option>a<optgroup label=g><option>b</optgroup><option>c</select> | <select><option>a</option><optgroup label="g"><option>b</option></optgroup><option>c</option></select>
			<select><optgroup label=g><option>a</optgroup><optgroup label=h></select> | <select><optgroup label="g"><option>a</option></optgroup><optgroup label="h"></optgroup></select>
			# </form> takes the form off the stack and leaves the elements open in it open (un.org's search form)
			<section><form><div><div><h2>t</h2><div>x</div></form><div class=a>y</div></section>z | <section><form><div><div><h2>t</h2><div>x</div><div class="a">y</div></div></div></form></section>z
			<div><form><p>a</form>b</div> | <div><form><p>a</p></form>b</div>
			<div><form><span>a</form>b</span>c</div> | <div><form><span>ab</span></form>c</div>
			<form><div>a</form><form><div>b</div></form>c | <form><div>a<form><div>b</div></form>c</div></form>
			# A form start tag is ignored while the form element pointer is set (lwn.net); a form in a table is empty
			<div><form id=c><input> text <p></div><div><form id=l><label>u<input></label></form><form id=s><input></form></div> | <div><form id="c"><input> text <p></p></form></div><div><label>u<input></label><form id="s"><input></form></div>
			<form id=a><table><tr><td></form>x</td></tr></table>y</form>z | <form id="a"><table><tbody><tr><td>x</td></tr></tbody></table>yz</form>
			<table><form><tr><td>a</td></tr></form></table> | <table><form></form><tbody><tr><td>a</td></tr></tbody></table>
			# Outside quirks mode a table closes an open p
			<p>a<table><tr><td>b</table>c | <p>a</p><table><tbody><tr><td>b</td></tr></tbody></table>c
			<p>a<span>s<table><tr><td>b</table>c | <p>a<span>s</span></p><table><tbody><tr><td>b</td></tr></tbody></table>c
			# A col goes into an implied colgroup; a colgroup closes an open one (w3.org)
			<table><col><col><tr><td>a</table> | <table><colgroup><col><col></colgroup><tbody><tr><td>a</td></tr></tbody></table>
			<table><colgroup><col></colgroup><col><tr><td>a</table> | <table><colgroup><col></colgroup><colgroup><col></colgroup><tbody><tr><td>a</td></tr></tbody></table>
			<table><col span=2><thead><tr><th>h</table> | <table><colgroup><col span="2"></colgroup><thead><tr><th>h</th></tr></thead></table>
			<table><colgroup span=1><colgroup span=3><colgroup span=3><thead><tr><th>h</table> | <table><colgroup span="1"></colgroup><colgroup span="3"></colgroup><colgroup span="3"></colgroup><thead><tr><th>h</th></tr></thead></table>
			# The options of a datalist are kept
			<datalist id=x><option value=a><option value=b></datalist>z | <datalist id="x"><option value="a"></option><option value="b"></option></datalist>z
			# A button keeps label, input, select, textarea, closes an open button, and bounds the p a block start tag closes
			<button class=t><label>Back to Top</label><img src=x.svg alt=i></button>z | <button class="t"><label>Back to Top</label><img src="x.svg" alt="i"></button>z
			<button><input type=text value=v></button>z | <button><input type="text" value="v"></button>z
			<button><select><option>a</option></select></button>z | <button><select><option>a</option></select></button>z
			<button><textarea>x</textarea></button>z | <button><textarea>x</textarea></button>z
			<button><label>a<input></label></button>z | <button><label>a<input></label></button>z
			<button>a<button>b</button>c | <button>a</button><button>b</button>c
			<p><button>a<p>b</button>c | <p><button>a<p>b</p></button>c</p>
			# </body> closes nothing: what follows, even after </html>, goes on in the body (vercel.com, material-web)
			<div class=x><p>a</p></div></body></html><div><p>b</p></div> | <div class="x"><p>a</p></div><div><p>b</p></div>
			<div><p>a</p></body></html><p>b</p> | <div><p>a</p><p>b</p></div>
			<p>a</body><p>b | <p>a</p><p>b</p>
			<p>a</p></body></html><head><title>t</title></head><body class=y><p>b</p></body></html> | <p>a</p><title>t</title><p>b</p>
			""")
	void sameTreeAsChrome(final String html, final String chrome) throws Exception {
		assertEquals(chrome, body("<!DOCTYPE html>", html, "legacy.xml"), "legacy.xml");
		assertEquals(chrome, body("<!DOCTYPE html>", html, "html4.xml"), "html4.xml");
		assertEquals(chrome, body("<!DOCTYPE html>", html, "legacy.xml>html4.xml"), "legacy.xml, html4.xml from body");
	}

	/**
	 * Only in quirks mode does a table leave an open p open. The mode comes from the doctype as the HTML Standard
	 * derives it (Chrome 151's document.compatMode: BackCompat for none, HTML 4.01 Transitional without a system
	 * identifier and HTML 3.2). With legacy.xml, the rules foliojet keeps for documents it does not take for
	 * standards mode; html4.xml always closes the p.
	 */
	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			'' | <p>a<table><tbody><tr><td>b</td></tr></tbody></table>c</p>
			<!DOCTYPE html> | <p>a</p><table><tbody><tr><td>b</td></tr></tbody></table>c
			<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01//EN"> | <p>a</p><table><tbody><tr><td>b</td></tr></tbody></table>c
			<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN"> | <p>a<table><tbody><tr><td>b</td></tr></tbody></table>c</p>
			<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd"> | <p>a</p><table><tbody><tr><td>b</td></tr></tbody></table>c
			<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd"> | <p>a</p><table><tbody><tr><td>b</td></tr></tbody></table>c
			<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN"> | <p>a<table><tbody><tr><td>b</td></tr></tbody></table>c</p>
			""")
	void tableClosesParagraphOutsideQuirksMode(final String doctype, final String chrome) throws Exception {
		assertEquals(chrome, body(doctype, "<p>a<table><tr><td>b</table>c", "legacy.xml"));
	}
}
