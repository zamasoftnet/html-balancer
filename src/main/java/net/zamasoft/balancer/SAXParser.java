package net.zamasoft.balancer;

import org.htmlunit.cyberneko.HTMLComponent;
import org.htmlunit.cyberneko.HTMLConfiguration;
import org.htmlunit.cyberneko.filters.NamespaceBinder;
import org.htmlunit.cyberneko.xerces.parsers.AbstractSAXParser;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLDocumentFilter;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLDocumentSource;
import org.htmlunit.cyberneko.xerces.xni.parser.XMLConfigurationException;

/**
 * A SAXParser that uses a custom tag balancer.
 * 
 * @author MIYABE Tatsuhiko
 * @version $Id: SAXParser.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class SAXParser extends AbstractSAXParser {
	private static final String NAMESPACES = "http://xml.org/sax/features/namespaces";
	private static final String FILTERS = "http://cyberneko.org/html/properties/filters";

	public SAXParser() {
		super(new BalancerHTMLConfiguration());
		try {
			final TagBalancer balancer = new TagBalancer();
			XMLDocumentFilter[] filters = { balancer };
			this.setProperty(FILTERS, filters);
		} catch (Exception e) {
			// ignore
		}
	}

	private static class BalancerHTMLConfiguration extends HTMLConfiguration {
		protected void reset() throws XMLConfigurationException {
			for (HTMLComponent component : this.getHtmlComponents()) {
				component.reset(this);
			}

			XMLDocumentSource source = this.getDocumentScanner();
			if (this.getFeature(NAMESPACES)) {
				NamespaceBinder namespaceBinder = this.getNamespaceBinder();
				source.setDocumentHandler(namespaceBinder);
				namespaceBinder.setDocumentSource(source);
				source = namespaceBinder;
			}

			XMLDocumentFilter[] filters = (XMLDocumentFilter[]) this.getProperty(FILTERS);
			if (filters != null) {
				for (XMLDocumentFilter filter : filters) {
					source.setDocumentHandler(filter);
					filter.setDocumentSource(source);
					source = filter;
				}
			}
			source.setDocumentHandler(this.getDocumentHandler());
		}
	}
}


