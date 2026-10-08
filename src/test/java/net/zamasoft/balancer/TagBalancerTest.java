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
 * whatwg-tables) and options inside an {@code optgroup} (they used to close the group: rails-guides).
 * </p>
 */
class TagBalancerTest {
	/** Serializes the body content: lower-case names, attributes in source order, text as is. */
	private static String body(final String html) throws Exception {
		final StringBuilder out = new StringBuilder();
		final boolean[] inBody = { false };
		final SAXParser parser = new SAXParser();
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
				} else if (inBody[0]) {
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
				new StringReader("<!DOCTYPE html><html><head></head><body>" + html + "</body></html>")));
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
			""")
	void sameTreeAsChrome(final String html, final String chrome) throws Exception {
		assertEquals(chrome, body(html));
	}
}
