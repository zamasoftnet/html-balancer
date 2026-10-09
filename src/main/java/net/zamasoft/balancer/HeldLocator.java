package net.zamasoft.balancer;

import org.htmlunit.cyberneko.xerces.xni.XMLLocator;

/**
 * The locator the balancer hands on: the scanner's, except while held-back events of a table are sent on, when it
 * reports the position each event had when it was read (2026-10-09). Downstream filters take source offsets from the
 * locator at each event (Copper's source replay of table cells relies on them).
 */
final class HeldLocator implements XMLLocator {
	/** A position in the source: character offset, line and column. */
	record Position(int characterOffset, int lineNumber, int columnNumber) {
	}

	private final XMLLocator locator;

	private Position held;

	HeldLocator(final XMLLocator locator) {
		this.locator = locator;
	}

	/** The position now: the held one while events are sent on, else the scanner's. */
	Position position() {
		if (this.held != null) {
			return this.held;
		}
		if (this.locator == null) {
			return null;
		}
		return new Position(this.locator.getCharacterOffset(), this.locator.getLineNumber(),
				this.locator.getColumnNumber());
	}

	/** The position held now, or null when the scanner's is reported. */
	Position held() {
		return this.held;
	}

	/** Reports {@code position} until it is set to null (the scanner's again). */
	void hold(final Position position) {
		this.held = position;
	}

	@Override
	public int getCharacterOffset() {
		return this.held != null ? this.held.characterOffset() : this.locator.getCharacterOffset();
	}

	@Override
	public int getLineNumber() {
		return this.held != null ? this.held.lineNumber() : this.locator.getLineNumber();
	}

	@Override
	public int getColumnNumber() {
		return this.held != null ? this.held.columnNumber() : this.locator.getColumnNumber();
	}

	@Override
	public String getLiteralSystemId() {
		return this.locator.getLiteralSystemId();
	}

	@Override
	public String getBaseSystemId() {
		return this.locator.getBaseSystemId();
	}

	@Override
	public String getPublicId() {
		return this.locator.getPublicId();
	}

	@Override
	public String getSystemId() {
		return this.locator.getSystemId();
	}

	@Override
	public String getXMLVersion() {
		return this.locator.getXMLVersion();
	}

	@Override
	public String getEncoding() {
		return this.locator.getEncoding();
	}
}
