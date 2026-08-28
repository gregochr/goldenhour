package com.gregochr.goldenhour.model.comingup;

/**
 * The surprise-score band edges (plan §13), lower-inclusive: a score of exactly {@code list}
 * already clears the "in the list" band, and so on up through {@code announce} and
 * {@code interrupt}.
 *
 * <p>Ships with P2's placeholder values so the response shape is complete; P5's pre-ship census
 * re-sets the values from a synthetic year of the assembled feed before the badge goes live. Null on
 * {@link ComingUpResponse} until P2.
 *
 * @param list      the "in the list" band's lower edge, in bits
 * @param announce  the "announced" band's lower edge, in bits
 * @param interrupt the "interrupt" band's lower edge, in bits
 */
public record ComingUpBands(double list, double announce, double interrupt) {
}
