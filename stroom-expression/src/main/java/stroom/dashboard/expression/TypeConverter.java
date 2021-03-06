/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.dashboard.expression;

import java.math.BigDecimal;

import stroom.util.date.DateUtil;

public final class TypeConverter {
    private TypeConverter() {
        // Utility class
    }

    public static Double getDouble(final Object obj) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof Double) {
            return (Double) obj;
        }

        try {
            return Double.parseDouble(obj.toString());
        } catch (final Exception e) {
        }

        try {
            return Double.valueOf(DateUtil.parseNormalDateTimeString(obj.toString()));
        } catch (final Exception e) {
        }

        return null;
    }

    public static String getString(final Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof String) {
            return (String) obj;
        }
        if (obj instanceof Double) {
            final Double dbl = (Double) obj;
            return doubleToString(dbl);
        }

        return obj.toString();
    }

	public static String doubleToString(final Double dbl) {
		// TODO : This is a temporary fix for Java 7, remove after move to Java
		// 8.
		if (dbl == 0D) {
			return "0";
		}

		final BigDecimal bigDecimal = BigDecimal.valueOf(dbl);
		return bigDecimal.stripTrailingZeros().toPlainString();
	}

    public static String escape(final String string) {
        return "'" + string.replaceAll("'", "''") + "'";
    }

    public static String unescape(final String string) {
        // Trim off containing quotes if the slice represents a single string.
        //
        // In some circumstances a string might contain two single quotes as the
        // first is used to escape a second. If this is the case then we want to
        // remove the escaping quote.
        return string.substring(1, string.length() - 1).replaceAll("''", "'");
    }
}
