package net.zamasoft.balancer;

import org.htmlunit.cyberneko.xerces.xni.Augmentations;

/**
 * Immutable copy of HtmlUnit NekoHTML augmentation location data.
 */
public class AugmentationsImpl implements Augmentations {
	private final int beginLineNumber;
	private final int beginColumnNumber;
	private final int beginCharacterOffset;
	private final int endLineNumber;
	private final int endColumnNumber;
	private final int endCharacterOffset;
	private final boolean synthesized;

	public AugmentationsImpl() {
		this(-1, -1, -1, -1, -1, -1, false);
	}

	public AugmentationsImpl(final Augmentations augs) {
		this(augs.getBeginLineNumber(), augs.getBeginColumnNumber(), augs.getBeginCharacterOffset(),
				augs.getEndLineNumber(), augs.getEndColumnNumber(), augs.getEndCharacterOffset(), augs.isSynthesized());
	}

	private AugmentationsImpl(int beginLineNumber, int beginColumnNumber, int beginCharacterOffset, int endLineNumber,
			int endColumnNumber, int endCharacterOffset, boolean synthesized) {
		this.beginLineNumber = beginLineNumber;
		this.beginColumnNumber = beginColumnNumber;
		this.beginCharacterOffset = beginCharacterOffset;
		this.endLineNumber = endLineNumber;
		this.endColumnNumber = endColumnNumber;
		this.endCharacterOffset = endCharacterOffset;
		this.synthesized = synthesized;
	}

	public int getBeginLineNumber() {
		return this.beginLineNumber;
	}

	public int getBeginColumnNumber() {
		return this.beginColumnNumber;
	}

	public int getBeginCharacterOffset() {
		return this.beginCharacterOffset;
	}

	public int getEndLineNumber() {
		return this.endLineNumber;
	}

	public int getEndColumnNumber() {
		return this.endColumnNumber;
	}

	public int getEndCharacterOffset() {
		return this.endCharacterOffset;
	}

	public boolean isSynthesized() {
		return this.synthesized;
	}

	public Augmentations clone() {
		return new AugmentationsImpl(this.beginLineNumber, this.beginColumnNumber, this.beginCharacterOffset,
				this.endLineNumber, this.endColumnNumber, this.endCharacterOffset, this.synthesized);
	}
}
