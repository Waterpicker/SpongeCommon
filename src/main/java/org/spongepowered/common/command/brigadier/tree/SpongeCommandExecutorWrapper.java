/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.command.brigadier.tree;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import org.spongepowered.api.command.CommandExecutor;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.common.command.manager.SpongeCommandCause;

public class SpongeCommandExecutorWrapper implements Command<SpongeCommandCause> {

    private static final Text ERROR_MESSAGE = Text.of(TextColors.RED, "Error running command: ");
    private final CommandExecutor executor;

    public SpongeCommandExecutorWrapper(CommandExecutor executor) {
        this.executor = executor;
    }

    @Override
    public int run(CommandContext<SpongeCommandCause> context) {
        try {
            return this.executor.execute((org.spongepowered.api.command.parameter.CommandContext) context).getResult();
        } catch (CommandException e) {
            // Print the error message here
            ((org.spongepowered.api.command.parameter.CommandContext) context).getMessageChannel().send(Text.of(ERROR_MESSAGE, e.getText()));

            // Now return zero, as required by Brigadier.
            return 0;
        }
    }
}
