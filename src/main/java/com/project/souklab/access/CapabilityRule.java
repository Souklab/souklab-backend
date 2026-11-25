package com.project.souklab.access;

import java.util.Set;

/** Adds the capabilities granted by one policy family to a viewer context. */
public interface CapabilityRule {
    void apply(CapabilityRuleContext context, Set<Capability> capabilities);
}
