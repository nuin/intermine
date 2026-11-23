package fixture;

/*
 * Copyright (C) 2002-2022 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

/**
 * Java 21 syntax accepted by the compiler and the project's style gate.
 * @author InterMine developers
 */
public final class ModernJava
{
    private ModernJava() {
    }

    /**
     * Describe a nonblank string or a coordinate pair.
     * @param value input value, including null
     * @return a description
     */
    public static String describe(Object value) {
        return switch (value) {
            case String text when !text.isBlank() -> text;
            case Point(int x, int y) -> Integer.toString(x + y);
            case null, default -> """
                    unknown
                    """.strip();
        };
    }

    /**
     * Validate a value with the existing declaration-only exception-doc policy.
     * @param value required value
     */
    public static void validate(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("A value is required");
        }
    }

    /**
     * Integer coordinates used by the record pattern.
     * @param x horizontal coordinate
     * @param y vertical coordinate
     * @author InterMine developers
     */
    public record Point(int x, int y) { }
}
