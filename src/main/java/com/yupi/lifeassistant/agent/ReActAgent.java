package com.yupi.lifeassistant.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public abstract class ReActAgent extends BaseAgent {

    public abstract boolean think();

    public abstract String act();

    @Override
    public String step() {
        boolean shouldAct = think();
        if (!shouldAct) {
            return "Thinking complete. No tool action is required.";
        }
        return act();
    }
}
