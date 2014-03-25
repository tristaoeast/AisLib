/* Copyright (c) 2011 Danish Maritime Authority
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dma.ais.packet;

import com.google.common.collect.ImmutableSet;
import dk.dma.ais.message.NavigationalStatus;
import dk.dma.ais.message.ShipTypeCargo;
import dk.dma.ais.packet.AisPacketTags.SourceType;
import dk.dma.enav.model.Country;
import dk.dma.enav.util.function.Predicate;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterBaseVisitor;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterLexer;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterParser;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterParser.OrAndContext;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterParser.ParensContext;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterParser.ProgContext;
import dk.dma.internal.ais.generated.parser.sourcefilter.SourceFilterParser.SourceBasestationContext;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * 
 * @author Kasper Nielsen
 */
class AisPacketFiltersSourceFilterParser {
    static Predicate<AisPacket> parseSourceFilter(String filter) {
        ANTLRInputStream input = new ANTLRInputStream(requireNonNull(filter));
        SourceFilterLexer lexer = new SourceFilterLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SourceFilterParser parser = new SourceFilterParser(tokens);

        // Better errors
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(new VerboseListener());
        parser.addErrorListener(new VerboseListener());

        ProgContext tree = parser.prog();
        return tree.filterExpression().accept(new SourceFilterToPredicateVisitor());
    }

    static class VerboseListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                String msg, RecognitionException e) {
            throw new IllegalArgumentException(msg + " @ character " + charPositionInLine);
            // if (recognizer instanceof Parser)
            // List<String> stack = ((Parser) recognizer).getRuleInvocationStack();
            // Collections.reverse(stack);
            // System.err.println("rule stack: " + stack);
            // System.err.println("line " + line + ":" + charPositionInLine + " at " + offendingSymbol + ": " + sentenceStr);
        }
    }

    static class SourceFilterToPredicateVisitor extends SourceFilterBaseVisitor<Predicate<AisPacket>> {

        @Override
        public Predicate<AisPacket> visitOrAnd(OrAndContext ctx) {
            return ctx.op.getType() ==
                    SourceFilterParser.AND ?
                        visit(ctx.filterExpression(0)).and(visit(ctx.filterExpression(1))) :
                        visit(ctx.filterExpression(0)).or(visit(ctx.filterExpression(1)));
        }

        @Override
        public Predicate<AisPacket> visitParens(ParensContext ctx) {
            final Predicate<AisPacket> p = visit(ctx.filterExpression());
            return new Predicate<AisPacket>() {
                public boolean test(AisPacket element) {
                    return p.test(element);
                }

                public String toString() {
                    return "(" + p.toString() + ")";
                }
            };
        }

        //
        // Tokens related to source
        //

        @Override
        public Predicate<AisPacket> visitSourceBasestation(@NotNull SourceBasestationContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Integer id = Integer.valueOf(ctx.INT().getText());
            return createFilterPredicateForComparison(fieldName, operator, id);
        }

        @Override
        public Predicate<AisPacket> visitSourceBasestationInIntList(@NotNull SourceFilterParser.SourceBasestationInIntListContext ctx) {
            String fieldName = ctx.getStart().getText();
            Integer[] ints = extractIntegers(ctx.intList().INT());
            return createFilterPredicateForList(fieldName, ints);
        }

        @Override
        public Predicate<AisPacket> visitSourceBasestationInIntRange(@NotNull SourceFilterParser.SourceBasestationInIntRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.intRange().INT().get(0).getText());
            int max = Integer.valueOf(ctx.intRange().INT().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitSourceIdInStringList(@NotNull SourceFilterParser.SourceIdInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] labels = extractStrings(ctx.stringList().string());
            Predicate<AisPacket> filter = createFilterPredicateForList(fieldName, labels);
            if (ctx.notin() != null) {
                filter = filter.negate();
            }
            return filter;
        }

        @Override
        public Predicate<AisPacket> visitSourceTypeInStringList(@NotNull SourceFilterParser.SourceTypeInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] labels = extractStrings(ctx.stringList().string());
            SourceType[] sourceTypes = getSourceTypes(labels);
            Predicate<AisPacket> filter = createFilterPredicateForList(fieldName, sourceTypes);
            if (ctx.notin() != null) {
                filter = filter.negate();
            }
            return filter;
        }

        @Override
        public Predicate<AisPacket> visitSourceCountryInStringList(SourceFilterParser.SourceCountryInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] labels = extractStrings(ctx.stringList().string());
            Country[] countries = getCountries(labels);
            Predicate<AisPacket> filter = createFilterPredicateForList(fieldName, countries);
            if (ctx.notin() != null) {
                filter = filter.negate();
            }
            return filter;
        }

        @Override
        public Predicate<AisPacket> visitSourceRegionInStringList(SourceFilterParser.SourceRegionInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] regions = extractStrings(ctx.stringList().string());
            Predicate<AisPacket> filter = createFilterPredicateForList(fieldName, regions);
            if (ctx.notin() != null) {
                filter = filter.negate();
            }
            return filter;
        }

        //
        // Tokens related to message contents
        //
        
        @Override
        public Predicate<AisPacket> visitMessageId(@NotNull SourceFilterParser.MessageIdContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Integer id = Integer.valueOf(ctx.INT().getText());
            return createFilterPredicateForComparison(fieldName, operator, id);
        }

        @Override
        public Predicate<AisPacket> visitMessageMmsi(@NotNull SourceFilterParser.MessageMmsiContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Integer id = Integer.valueOf(ctx.INT().getText());
            return createFilterPredicateForComparison(fieldName, operator, id);
        }

        @Override
        public Predicate<AisPacket> visitMessageImo(@NotNull SourceFilterParser.MessageImoContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Integer id = Integer.valueOf(ctx.INT().getText());
            return createFilterPredicateForComparison(fieldName, operator, id);
        }

        @Override
        public Predicate<AisPacket> visitMessageShiptype(@NotNull SourceFilterParser.MessageShiptypeContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            String value = extractString(ctx.string());
            try {
                return createFilterPredicateForComparison(fieldName, operator, Integer.valueOf(value));
            } catch (NumberFormatException e) {
                Set<Integer> shipTypes = getShipTypes(value);
                if (!operator.equals("=")) {
                    throw new IllegalArgumentException("Sorry, only = operator currently supported.");
                }
                return createFilterPredicateForList(fieldName, shipTypes.toArray(new Integer[shipTypes.size()]));
            }
        }

        @Override
        public Predicate<AisPacket> visitMessageNavigationalStatus(@NotNull SourceFilterParser.MessageNavigationalStatusContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            String value = extractString(ctx.string());
            try {
                return createFilterPredicateForComparison(fieldName, operator, Integer.valueOf(value));
            } catch (NumberFormatException e) {
                Set<Integer> navstats = getNavigationalStatuses(new String[] { value });
                if (!operator.equals("=")) {
                    throw new IllegalArgumentException("Sorry, only = operator currently supported.");
                }
                return createFilterPredicateForList(fieldName, navstats.toArray(new Integer[navstats.size()]));
            }
        }

        @Override
        public Predicate<AisPacket> visitMessageName(@NotNull SourceFilterParser.MessageNameContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            String name = extractString(ctx.string());
            return createFilterPredicateForComparison(fieldName, operator, name);
        }

        @Override
        public Predicate<AisPacket> visitMessageCallsign(@NotNull SourceFilterParser.MessageCallsignContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            String name = extractString(ctx.string());
            return createFilterPredicateForComparison(fieldName, operator, name);
        }

        @Override
        public Predicate<AisPacket> visitMessageSpeedOverGround(@NotNull SourceFilterParser.MessageSpeedOverGroundContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Float sog = Float.valueOf(ctx.number().getText());
            return createFilterPredicateForComparison(fieldName, operator, sog);
        }

        @Override
        public Predicate<AisPacket> visitMessageCourseOverGround(@NotNull SourceFilterParser.MessageCourseOverGroundContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Float cog = Float.valueOf(ctx.number().getText());
            return createFilterPredicateForComparison(fieldName, operator, cog);
        }

        @Override
        public Predicate<AisPacket> visitMessageTrueHeading(@NotNull SourceFilterParser.MessageTrueHeadingContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Integer hdg = Integer.valueOf(ctx.number().getText());
            return createFilterPredicateForComparison(fieldName, operator, hdg);
        }

        @Override
        public Predicate<AisPacket> visitMessageDraught(@NotNull SourceFilterParser.MessageDraughtContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Float draught = Float.valueOf(ctx.number().getText());
            return createFilterPredicateForComparison(fieldName, operator, draught);
        }

        @Override
        public Predicate<AisPacket> visitMessageLongitude(@NotNull SourceFilterParser.MessageLongitudeContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Float lon = Float.valueOf(ctx.number().getText());
            return createFilterPredicateForComparison(fieldName, operator, lon);
        }

        @Override
        public Predicate<AisPacket> visitMessageLatitude(@NotNull SourceFilterParser.MessageLatitudeContext ctx) {
            String fieldName = ctx.getStart().getText();
            String operator = ctx.compareTo().getText();
            Float lon = Float.valueOf(ctx.number().getText());
            return createFilterPredicateForComparison(fieldName, operator, lon);
        }

        @Override
        public Predicate<AisPacket> visitMessageIdInIntList(@NotNull SourceFilterParser.MessageIdInIntListContext ctx) {
            String fieldName = ctx.getStart().getText();
            Integer[] ints = extractIntegers(ctx.intList().INT());
            return createFilterPredicateForList(fieldName, ints);
        }

        @Override
        public Predicate<AisPacket> visitMessageMmsiInIntList(@NotNull SourceFilterParser.MessageMmsiInIntListContext ctx) {
            String fieldName = ctx.getStart().getText();
            Integer[] ints = extractIntegers(ctx.intList().INT());
            return createFilterPredicateForList(fieldName, ints);
        }

        @Override
        public Predicate<AisPacket> visitMessageImoInIntList(@NotNull SourceFilterParser.MessageImoInIntListContext ctx) {
            String fieldName = ctx.getStart().getText();
            Integer[] ints = extractIntegers(ctx.intList().INT());
            return createFilterPredicateForList(fieldName, ints);
        }

        @Override
        public Predicate<AisPacket> visitMessageShiptypeInIntList(@NotNull SourceFilterParser.MessageShiptypeInIntListContext ctx) {
            String fieldName = ctx.getStart().getText();
            Integer[] ints = extractIntegers(ctx.intList().INT());
            return createFilterPredicateForList(fieldName, ints);
        }

        @Override
        public Predicate<AisPacket> visitMessageNavigationalStatusInIntList(@NotNull SourceFilterParser.MessageNavigationalStatusInIntListContext ctx) {
            String fieldName = ctx.getStart().getText();
            Integer[] ints = extractIntegers(ctx.intList().INT());
            return createFilterPredicateForList(fieldName, ints);
        }

        @Override
        public Predicate<AisPacket> visitMessageNameInStringList(@NotNull SourceFilterParser.MessageNameInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] strings = extractStrings(ctx.stringList().string());
            return createFilterPredicateForList(fieldName, strings);
        }

        @Override
        public Predicate<AisPacket> visitMessageCallsignInStringList(@NotNull SourceFilterParser.MessageCallsignInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] strings = extractStrings(ctx.stringList().string());
            return createFilterPredicateForList(fieldName, strings);
        }

        @Override
        public Predicate<AisPacket> visitMessageIdInIntRange(@NotNull SourceFilterParser.MessageIdInIntRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.intRange().INT().get(0).getText());
            int max = Integer.valueOf(ctx.intRange().INT().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageMmsiInIntRange(@NotNull SourceFilterParser.MessageMmsiInIntRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.intRange().INT().get(0).getText());
            int max = Integer.valueOf(ctx.intRange().INT().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageImoInIntRange(@NotNull SourceFilterParser.MessageImoInIntRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.intRange().INT().get(0).getText());
            int max = Integer.valueOf(ctx.intRange().INT().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageShiptypeInIntRange(@NotNull SourceFilterParser.MessageShiptypeInIntRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.intRange().INT().get(0).getText());
            int max = Integer.valueOf(ctx.intRange().INT().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageNavigationalStatusInIntRange(@NotNull SourceFilterParser.MessageNavigationalStatusInIntRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.intRange().INT().get(0).getText());
            int max = Integer.valueOf(ctx.intRange().INT().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageTrueHeadingInNumberRange(@NotNull SourceFilterParser.MessageTrueHeadingInNumberRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            int min = Integer.valueOf(ctx.numberRange().number().get(0).getText());
            int max = Integer.valueOf(ctx.numberRange().number().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageLatitudeInNumberRange(@NotNull SourceFilterParser.MessageLatitudeInNumberRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            float min = Float.valueOf(ctx.numberRange().number().get(0).getText());
            float max = Float.valueOf(ctx.numberRange().number().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageLongitudeInNumberRange(@NotNull SourceFilterParser.MessageLongitudeInNumberRangeContext ctx) {
            String fieldName = ctx.getStart().getText();
            float min = Float.valueOf(ctx.numberRange().number().get(0).getText());
            float max = Float.valueOf(ctx.numberRange().number().get(1).getText());
            return createFilterPredicateForRange(fieldName, min, max);
        }

        @Override
        public Predicate<AisPacket> visitMessageShiptypeInStringList(@NotNull SourceFilterParser.MessageShiptypeInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] labels = extractStrings(ctx.stringList().string());
            Set<Integer> shipTypes = getShipTypes(labels);
            return createFilterPredicateForList(fieldName, shipTypes.toArray(new Integer[shipTypes.size()]));
        }

        @Override
        public Predicate<AisPacket> visitMessageNavigationalStatusInStringList(@NotNull SourceFilterParser.MessageNavigationalStatusInStringListContext ctx) {
            String fieldName = ctx.getStart().getText();
            String[] labels = extractStrings(ctx.stringList().string());
            Set<Integer> navstats = getNavigationalStatuses(labels);
            return createFilterPredicateForList(fieldName, navstats.toArray(new Integer[navstats.size()]));
        }

        /**
         * Extract an array of Integers from an ANTLR list of TerminalNodes
         * @param ints
         * @return
         */
        private static Integer[] extractIntegers(List<TerminalNode> ints) {
            int n = ints.size();
            Integer integers[] = new Integer[n];
            for (int i = 0; i<n; i++) {
                integers[i] = Integer.valueOf(ints.get(i).getText());
            }
            return integers;
        }

        /**
         * Extract an array of Strings from ANTLR list
         * @param strs
         * @return
         */
        private static String[] extractStrings(List strs) {
            int n = strs.size();
            String strings[] = new String[n];
            for (int i = 0; i<n; i++) {
                Object o = strs.get(i);
                if (o instanceof TerminalNode) {
                    strings[i] = removeSurroundingApostrophes(((TerminalNode) o).getText());
                } else if (o instanceof SourceFilterParser.StringContext) {
                    strings[i] = removeSurroundingApostrophes(((SourceFilterParser.StringContext) o).getText());
                } else {
                    throw new IllegalArgumentException(o.getClass().toString());
                }
            }
            return strings;
        }

        /**
         * Extract string value from a 'string' token
         * @param string
         * @return
         */
        private String extractString(SourceFilterParser.StringContext string) {
            String result = null;
            if (string.STRING() != null) {
                result = removeSurroundingApostrophes(string.STRING().getText());
            } else if (string.WORD() != null) {
                result = string.WORD().getText();
            } else if (string.number() != null) {
                result = string.number().getText();
            }
            return result;
        }

        /**
         * Create a new predicate which will perform a comparison of the given field in an AisMessage to the given value.
         * @param fieldToken the field to compare
         * @param operator the type of comparison to make (=, !=, >, >=, <=, <)
         * @param value the fixed value to compare against
         * @return true if filter is passed or indeterminate; false if filter blocks.
         */
        private <T> Predicate<AisPacket> createFilterPredicateForComparison(@NotNull String fieldToken, @NotNull String operator, @NotNull T value) {
            Predicate<AisPacket> filter = null;
            String filterFactoryMethodName = mapFieldTokenToFilterFactoryMethodName(fieldToken);
            try {
                Method filterFactoryMethod = AisPacketFilters.class.getDeclaredMethod(filterFactoryMethodName, AisPacketFilters.Operator.class, value.getClass());

                if ("=".equals(operator)) {
                    filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, AisPacketFilters.Operator.EQUALS, value);
                } else if ("!=".equals(operator)) {
                    filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, AisPacketFilters.Operator.NOT_EQUALS, value);
                } else if (">".equals(operator)) {
                    filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, AisPacketFilters.Operator.GREATER_THAN, value);
                } else if (">=".equals(operator)) {
                    filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, AisPacketFilters.Operator.GREATER_THAN_OR_EQUALS, value);
                } else if ("<".equals(operator)) {
                    filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, AisPacketFilters.Operator.LESS_THAN, value);
                } else if ("<=".equals(operator)) {
                    filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, AisPacketFilters.Operator.LESS_THAN_OR_EQUALS, value);
                } else {
                    throw new IllegalArgumentException("Operator " + operator + " not implemented.");
                }
            } catch (InvocationTargetException e) {
                e.printStackTrace(System.err);
            } catch (NoSuchMethodException e) {
                e.printStackTrace(System.err);
            } catch (IllegalAccessException e) {
                e.printStackTrace(System.err);
            }
            return filter;
        }

        /**
         * Create new predicate to filter for given field to be in a range of values.
         * @param fieldName the field to filter
         * @param min the minimum value that the field must have to pass the filter.
         * @param max the maximum value that the field must have to pass the filter.
         * @return true if filter is passed or indeterminate; false if filter blocks.
         */
        private Predicate<AisPacket> createFilterPredicateForRange(@NotNull String fieldName, int min, int max) {
            if (max < min) {
                throw new IllegalArgumentException("max < min");
            }
            Predicate<AisPacket> filter = null;
            try {
                String filterFactoryMethodName = mapFieldTokenToFilterFactoryMethodName(fieldName) + "InRange";
                Method filterFactoryMethod = AisPacketFilters.class.getDeclaredMethod(filterFactoryMethodName, int.class, int.class);
                filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, min, max);
            } catch (NoSuchMethodException e) {
                e.printStackTrace(System.err);
            } catch (InvocationTargetException e) {
                e.printStackTrace(System.err);
            } catch (IllegalAccessException e) {
                e.printStackTrace(System.err);
            }
            return filter;
        }

        /**
         * Create new predicate to filter for given field to be in a range of values.
         * @param fieldName the field to filter
         * @param min the minimum value that the field must have to pass the filter.
         * @param max the maximum value that the field must have to pass the filter.
         * @return true if filter is passed or indeterminate; false if filter blocks.
         */
        private Predicate<AisPacket> createFilterPredicateForRange(@NotNull String fieldName, float min, float max) {
            if (max < min) {
                throw new IllegalArgumentException("max < min");
            }
            Predicate<AisPacket> filter = null;
            try {
                String filterFactoryMethodName = mapFieldTokenToFilterFactoryMethodName(fieldName) + "InRange";
                Method filterFactoryMethod = AisPacketFilters.class.getDeclaredMethod(filterFactoryMethodName, float.class, float.class);
                filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, min, max);
            } catch (NoSuchMethodException e) {
                e.printStackTrace(System.err);
            } catch (InvocationTargetException e) {
                e.printStackTrace(System.err);
            } catch (IllegalAccessException e) {
                e.printStackTrace(System.err);
            }
            return filter;
        }

        /**
         * Create new predicate to filter for given field to be in list of allowed values.
         * @param fieldName the field to filter
         * @param list an array of allowed values for the field to pass the filter
         * @param <T>
         * @return true if filter is passed or indeterminate; false if filter blocks.
         */
        private <T> Predicate<AisPacket> createFilterPredicateForList(String fieldName, T[] list) {
            Predicate<AisPacket> filter = null;
            try {
                String filterFactoryMethodName = mapFieldTokenToFilterFactoryMethodName(fieldName) + "InList";
                Method filterFactoryMethod = AisPacketFilters.class.getDeclaredMethod(filterFactoryMethodName, list.getClass());
                if (list instanceof String[]) {
                    String preprocessedStrings[] = new String[list.length];
                    for (int i=0; i<list.length; i++) {
                        String preprocessedString = (String) list[i];
                        if (preprocessedString.startsWith("'")) {
                            preprocessedString = preprocessedString.substring(1);
                        }
                        if (preprocessedString.endsWith("'")) {
                            preprocessedString = preprocessedString.substring(0,preprocessedString.length()-1);
                        }
                        preprocessedStrings[i] = preprocessedString;
                    }
                    list = (T[]) preprocessedStrings;
                }
                filter = (Predicate<AisPacket>) filterFactoryMethod.invoke(null, (Object) list);
            } catch (NoSuchMethodException e) {
                e.printStackTrace(System.err);
            } catch (InvocationTargetException e) {
                e.printStackTrace(System.err);
            } catch (IllegalAccessException e) {
                e.printStackTrace(System.err);
            }
            return filter;
        }

        /**
         * Map grammar field tokens to names of filter predicates
         * @param fieldToken
         * @return name of filter predicate.
         */
        private static String mapFieldTokenToFilterFactoryMethodName(String fieldToken) {
            switch(fieldToken) {
                case "s.id":
                    return "filterOnSourceId";
                case "s.bs":
                    return "filterOnSourceBasestation";
                case "s.country":
                    return "filterOnSourceCountry";
                case "s.type":
                    return "filterOnSourceType";
                case "s.region":
                    return "filterOnSourceRegion";
                case "m.id":
                    return "filterOnMessageId";
                case "m.mmsi":
                    return "filterOnMessageMmsi";
                case "m.imo":
                    return "filterOnMessageImo";
                case "m.type":
                    return "filterOnMessageShiptype";
                case "m.navstat":
                    return "filterOnMessageNavigationalStatus";
                case "m.name":
                    return "filterOnMessageName";
                case "m.cs":
                    return "filterOnMessageCallsign";
                case "m.sog":
                    return "filterOnMessageSpeedOverGround";
                case "m.cog":
                    return "filterOnMessageCourseOverGround";
                case "m.hdg":
                    return "filterOnMessageTrueHeading";
                case "m.lon":
                    return "filterOnMessageLongitude";
                case "m.lat":
                    return "filterOnMessageLatitude";
                case "m.draught":
                    return "filterOnMessageDraught";
            }
            throw new IllegalArgumentException("No mapping to predicate name for field " + fieldToken);
        }

    }

    private static String removeSurroundingApostrophes(String string) {
        String result = string;
        if (result.startsWith("'")) {
            result = result.substring(1);
        }
        if (result.endsWith("'")) {
            result = result.substring(0, result.length()-1);
        }
        return result;
    }

    /**
     * Get the list of AIS ship types matching the label text.
     * Based on Rec. ITU-R M.1371-4 - table 50.
     * @param labels the ship type label; e.g. 'fishing'.
     * @return a list of matching ship types.
     */
    private static Set<Integer> getShipTypes(String[] labels) {
        HashSet<Integer> shipTypes = new HashSet<>();
        for (String label : labels) {
            shipTypes.addAll(getShipTypes(label));
        }
        return shipTypes;
    }

    /**
     * Get the list of AIS ship types matching the label text.
     * Based on Rec. ITU-R M.1371-4 - table 50.
     * @param label the ship type label; e.g. 'fishing'.
     * @return a list of matching ship types.
     */
    private static ImmutableSet<Integer> getShipTypes(String label) {
        HashSet<Integer> shipTypes = new HashSet<>();
        for (int i=0; i<100; i++) {
            ShipTypeCargo shipTypeCargo = new ShipTypeCargo(i);
            if (shipTypeCargo.getShipType().toString().equalsIgnoreCase(label)) {
                shipTypes.add(i);
            }
        }
        return ImmutableSet.copyOf(shipTypes);
    }

    /**
     * Get the list of AIS navigational statuses matching the label text.
     * Based on Rec. ITU-R M.1371-4 - table 45.
     * @param labels
     * @return
     */
    private static ImmutableSet<Integer> getNavigationalStatuses(String[] labels) {
        HashSet<Integer> navstats = new HashSet<>();
        for (String label : labels) {
            try {
                navstats.add(NavigationalStatus.valueOf(label).getCode());
            } catch (IllegalArgumentException e) {
                System.out.println("WARN: " + e.getMessage());
            }
        }
        return ImmutableSet.copyOf(navstats);
    }

    private static SourceType[] getSourceTypes(String[] labels) {
        ArrayList<SourceType> r = new ArrayList<>();
        for (String label : labels) {
            r.add(SourceType.fromString(label));
        }
        return r.toArray(new SourceType[r.size()]);
    }

    private static Country[] getCountries(String[] countryCodes) {
        List<Country> countries = Country.findAllByCode(countryCodes);
        return countries.toArray(new Country[countries.size()]);
    }

}
