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

import java.io.IOException;
import java.util.List;

/**
 * Deliberate violations used to ensure the style gate still rejects bad input.
 * @author InterMine developers
 */
public class BadStyle
{
    public int undocumented;

    public int add(int left,int right) {
        return left + right;
    }

    /** Declared checked exceptions require documentation. */
    public void fail() throws IOException {
        throw new IOException();
    }

    int packageMethod() {
        return 1;
    }

    public static class Undocumented
    {
    }
}
