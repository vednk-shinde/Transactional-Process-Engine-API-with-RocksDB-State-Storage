package com.example.processengine.agent;

import com.example.processengine.core.ProcessInstance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a small condition grammar against a process's current
 * variables: {@code <variable> <op> <value>} where op is one of
 * {@code > < >= <= == !=}. Deliberately not a general expression
 * language — the point is to prove the agent can make a real decision
 * based on live process state, not to build a rules engine. A production
 * version would likely delegate this to a proper expression library
 * (e.g. a small subset of SpEL or MVEL); this is a defensible, honestly
 * scoped stand-in.
 */
public final class ConditionEvaluator {
    private static final Pattern CONDITION =
            Pattern.compile("^\\s*(\\w+)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+(?:\\.\\d+)?|\\w+)\\s*$");

    private ConditionEvaluator() {}

    public static boolean evaluate(String conditionExpr, ProcessInstance instance) {
        if (conditionExpr == null || conditionExpr.isBlank()) {
            return true; // no condition given = unconditional resume
        }
        Matcher m = CONDITION.matcher(conditionExpr);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unparseable condition: " + conditionExpr);
        }
        String variable = m.group(1);
        String op = m.group(2);
        String rhsRaw = m.group(3);

        String lhsRaw = instance.getVariable(variable);
        if (lhsRaw == null) {
            throw new IllegalStateException("Condition references unknown variable: " + variable);
        }

        // Try numeric comparison first; fall back to string equality for == / != only.
        try {
            double lhs = Double.parseDouble(lhsRaw);
            double rhs = Double.parseDouble(rhsRaw);
            return switch (op) {
                case ">" -> lhs > rhs;
                case "<" -> lhs < rhs;
                case ">=" -> lhs >= rhs;
                case "<=" -> lhs <= rhs;
                case "==" -> lhs == rhs;
                case "!=" -> lhs != rhs;
                default -> throw new IllegalStateException("unreachable: " + op);
            };
        } catch (NumberFormatException notNumeric) {
            return switch (op) {
                case "==" -> lhsRaw.equals(rhsRaw);
                case "!=" -> !lhsRaw.equals(rhsRaw);
                default -> throw new IllegalArgumentException(
                        "Operator " + op + " requires numeric operands, got '" + lhsRaw + "' " + op + " '" + rhsRaw + "'");
            };
        }
    }
}
