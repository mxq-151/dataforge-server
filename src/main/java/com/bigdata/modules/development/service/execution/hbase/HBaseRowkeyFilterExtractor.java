package com.bigdata.modules.development.service.execution.hbase;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.RegexStringComparator;
import org.apache.hadoop.hbase.filter.SubstringComparator;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class HBaseRowkeyFilterExtractor {

    private static final Logger log = LoggerFactory.getLogger(HBaseRowkeyFilterExtractor.class);

    public static List<HBaseRowkeyCondition> extract(String sql) {
        try {
            net.sf.jsqlparser.statement.Statement stmt =
                    net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof net.sf.jsqlparser.statement.select.Select)) {
                return new ArrayList<>();
            }
            net.sf.jsqlparser.statement.select.Select select =
                    (net.sf.jsqlparser.statement.select.Select) stmt;
            if (!(select.getSelectBody() instanceof net.sf.jsqlparser.statement.select.PlainSelect)) {
                return new ArrayList<>();
            }
            net.sf.jsqlparser.statement.select.PlainSelect plainSelect =
                    (net.sf.jsqlparser.statement.select.PlainSelect) select.getSelectBody();
            if (plainSelect.getWhere() == null) {
                return new ArrayList<>();
            }

            List<HBaseRowkeyCondition> conditions = new ArrayList<>();
            extractFromExpression(plainSelect.getWhere(), conditions);

            log.info("从SQL提取到{}个rowkey过滤条件", conditions.size());
            return conditions;
        } catch (Exception e) {
            log.warn("解析rowkey过滤条件失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void extractFromExpression(Expression expr, List<HBaseRowkeyCondition> conditions) {
        if (expr instanceof AndExpression) {
            extractFromExpression(((AndExpression) expr).getLeftExpression(), conditions);
            extractFromExpression(((AndExpression) expr).getRightExpression(), conditions);
        } else if (expr instanceof OrExpression) {
            extractFromExpression(((OrExpression) expr).getLeftExpression(), conditions);
            extractFromExpression(((OrExpression) expr).getRightExpression(), conditions);
        } else if (isRowkeyComparison(expr)) {
            HBaseRowkeyCondition cond = parseComparison(expr);
            if (cond != null) {
                conditions.add(cond);
            }
        }
    }

    private static boolean isRowkeyComparison(Expression expr) {
        if (expr instanceof EqualsTo || expr instanceof NotEqualsTo ||
                expr instanceof GreaterThan || expr instanceof GreaterThanEquals ||
                expr instanceof MinorThan || expr instanceof MinorThanEquals ||
                expr instanceof LikeExpression || expr instanceof Between) {
            ComparisonOperator binaryExpr = (ComparisonOperator) expr;
            String leftColName = getColumnName(binaryExpr.getLeftExpression());
            String rightColName = getColumnName(binaryExpr.getRightExpression());
            return "ROWKEY".equalsIgnoreCase(leftColName) || "ROWKEY".equalsIgnoreCase(rightColName);
        }
        if (expr instanceof InExpression) {
            InExpression inExpr = (InExpression) expr;
            String colName = getColumnName(inExpr.getLeftExpression());
            return "ROWKEY".equalsIgnoreCase(colName);
        }
        return false;
    }

    private static String getColumnName(Expression expr) {
        if (expr instanceof Column) {
            return ((Column) expr).getColumnName();
        }
        return null;
    }

    private static HBaseRowkeyCondition parseComparison(Expression expr) {
        try {
            if (expr instanceof EqualsTo) {
                return parseBinary((EqualsTo) expr, "EQ");
            } else if (expr instanceof NotEqualsTo) {
                return parseBinary((NotEqualsTo) expr, "NOT_EQ");
            } else if (expr instanceof GreaterThan) {
                return parseBinary((GreaterThan) expr, "GT");
            } else if (expr instanceof GreaterThanEquals) {
                return parseBinary((GreaterThanEquals) expr, "GTE");
            } else if (expr instanceof MinorThan) {
                return parseBinary((MinorThan) expr, "LT");
            } else if (expr instanceof MinorThanEquals) {
                return parseBinary((MinorThanEquals) expr, "LTE");
            } else if (expr instanceof LikeExpression) {
                return parseLike((LikeExpression) expr);
            } else if (expr instanceof Between) {
                return parseBetween((Between) expr);
            } else if (expr instanceof InExpression) {
                return parseIn((InExpression) expr);
            }
        } catch (Exception e) {
            log.warn("解析rowkey条件失败: {}", e.getMessage());
        }
        return null;
    }

    private static HBaseRowkeyCondition parseBinary(ComparisonOperator expr, String opType) {
        boolean leftIsRowkey = "ROWKEY".equalsIgnoreCase(getColumnName(expr.getLeftExpression()));
        Expression valueExpr = leftIsRowkey ? expr.getRightExpression() : expr.getLeftExpression();
        String valueStr = stripQuotes(valueExpr.toString());

        ByteArrayComparable comparator = new SubstringComparator(valueStr);
        CompareFilter.CompareOp compareOp = mapCompareOp(opType);

        return new HBaseRowkeyCondition(opType, valueStr, compareOp, comparator,
                null, null, null);
    }

    private static HBaseRowkeyCondition parseLike(LikeExpression expr) {
        boolean leftIsRowkey = "ROWKEY".equalsIgnoreCase(getColumnName(expr.getLeftExpression()));
        Expression valueExpr = leftIsRowkey ? expr.getRightExpression() : expr.getLeftExpression();
        String pattern = stripQuotes(valueExpr.toString());

        if (pattern.endsWith("%") && !pattern.contains("_") && !pattern.substring(0, pattern.length() - 1).contains("%")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return new HBaseRowkeyCondition("PREFIX", prefix, null,
                    new SubstringComparator(prefix), null, null, null);
        }

        String regexPattern = likeToRegex(pattern);
        return new HBaseRowkeyCondition("LIKE_REGEX", pattern, null,
                new RegexStringComparator(regexPattern), null, null, null);
    }

    private static HBaseRowkeyCondition parseBetween(Between expr) {
        boolean leftIsRowkey = "ROWKEY".equalsIgnoreCase(getColumnName(expr.getLeftExpression()));
        if (!leftIsRowkey) {
            return null;
        }
        String startValue = stripQuotes(expr.getBetweenExpressionStart().toString());
        String endValue = stripQuotes(expr.getBetweenExpressionEnd().toString());
        return new HBaseRowkeyCondition("BETWEEN",
                startValue + ".." + endValue, null, null,
                Bytes.toBytes(startValue), Bytes.toBytes(endValue), null);
    }

    @SuppressWarnings("unchecked")
    private static HBaseRowkeyCondition parseIn(InExpression expr) {
        String colName = getColumnName(expr.getLeftExpression());
        if (!"ROWKEY".equalsIgnoreCase(colName)) {
            return null;
        }

        List<String> values = new ArrayList<>();
        try {
            java.lang.reflect.Method rightMethod = expr.getClass().getMethod("getRightItemsList");
            Object itemsObj = rightMethod.invoke(expr);
            if (itemsObj != null) {
                java.lang.reflect.Method getExprs = itemsObj.getClass().getMethod("getExpressions");
                Object exprsObj = getExprs.invoke(itemsObj);
                if (exprsObj instanceof List) {
                    for (Object item : (List<?>) exprsObj) {
                        if (item != null) {
                            values.add(stripQuotes(item.toString()));
                        }
                    }
                }
            }
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method rightExprMethod = expr.getClass().getMethod("getRightExpression");
                Object rightObj = rightExprMethod.invoke(expr);
                if (rightObj != null) {
                    java.lang.reflect.Method getExprs = rightObj.getClass().getMethod("getExpressions");
                    Object exprsObj = getExprs.invoke(rightObj);
                    if (exprsObj instanceof List) {
                        for (Object item : (List<?>) exprsObj) {
                            if (item != null) {
                                values.add(stripQuotes(item.toString()));
                            }
                        }
                    }
                }
            } catch (Exception ignored2) {
                log.warn("无法解析IN表达式: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("无法解析IN表达式列表: {}", e.getMessage());
        }

        byte[][] keysBytes = values.stream()
                .map(Bytes::toBytes)
                .toArray(byte[][]::new);
        return new HBaseRowkeyCondition("IN", String.join(",", values),
                null, null, null, null, keysBytes);
    }

    private static CompareFilter.CompareOp mapCompareOp(String opType) {
        switch (opType) {
            case "EQ": return CompareFilter.CompareOp.EQUAL;
            case "NOT_EQ": return CompareFilter.CompareOp.NOT_EQUAL;
            case "GT": return CompareFilter.CompareOp.GREATER;
            case "GTE": return CompareFilter.CompareOp.GREATER_OR_EQUAL;
            case "LT": return CompareFilter.CompareOp.LESS;
            case "LTE": return CompareFilter.CompareOp.LESS_OR_EQUAL;
            default: return CompareFilter.CompareOp.EQUAL;
        }
    }

    private static String likeToRegex(String likePattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            switch (c) {
                case '%':
                    regex.append(".*");
                    break;
                case '_':
                    regex.append(".");
                    break;
                case '.':
                case '*':
                case '+':
                case '?':
                case '^':
                case '$':
                case '\\':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '|':
                    regex.append('\\').append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        return regex.toString();
    }

    private static String stripQuotes(String s) {
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}