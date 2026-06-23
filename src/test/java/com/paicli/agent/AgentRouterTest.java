package com.paicli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRouterTest {

    @Test
    void routesSimpleExplanationToReact() {
        AgentRouter.RouteDecision decision = AgentRouter.route(
                "\u89e3\u91ca\u4e00\u4e0b Agent.java \u4e3b\u8981\u505a\u4ec0\u4e48");

        assertEquals(AgentRouter.ExecutionMode.REACT, decision.mode());
        assertFalse(decision.parallelCandidate());
        assertTrue(decision.score() <= 2);
    }

    @Test
    void routesSequentialImplementationToPlan() {
        AgentRouter.RouteDecision decision = AgentRouter.route(
                "\u5148\u5b9e\u73b0 Agent \u8def\u7531\uff0c\u7136\u540e\u8865\u5145\u6587\u6863\uff0c\u6700\u540e\u8fd0\u884c\u6d4b\u8bd5");

        assertEquals(AgentRouter.ExecutionMode.PLAN, decision.mode());
        assertFalse(decision.parallelCandidate());
        assertTrue(decision.score() >= 3);
    }

    @Test
    void routesIndependentParallelWorkToTeam() {
        AgentRouter.RouteDecision decision = AgentRouter.route(
                "\u540c\u65f6\u68c0\u67e5 cli \u548c rag \u4e24\u4e2a\u6a21\u5757\uff0c\u5206\u522b\u4fee\u590d\u95ee\u9898\u5e76\u8865\u6d4b\u8bd5");

        assertEquals(AgentRouter.ExecutionMode.TEAM, decision.mode());
        assertTrue(decision.parallelCandidate());
        assertTrue(decision.score() >= 6);
    }

    @Test
    void keepsBlankInputOnReact() {
        AgentRouter.RouteDecision decision = AgentRouter.route("   ");

        assertEquals(AgentRouter.ExecutionMode.REACT, decision.mode());
        assertEquals(0, decision.score());
        assertTrue(decision.reasons().isEmpty());
    }
}
