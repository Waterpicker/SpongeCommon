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
package org.spongepowered.common.command.manager;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.inject.Singleton;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.command.manager.CommandManager;
import org.spongepowered.api.command.manager.CommandMapping;
import org.spongepowered.api.command.manager.CommandFailedRegistrationException;
import org.spongepowered.api.command.parameter.Parameter;
import org.spongepowered.api.command.registrar.CommandRegistrar;
import org.spongepowered.api.event.CauseStackManager;
import org.spongepowered.api.event.cause.EventContextKeys;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.service.permission.Subject;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.text.channel.MessageReceiver;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.command.registrar.SpongeManagedCommandRegistrar;
import org.spongepowered.common.command.registrar.SpongeRawCommandRegistrar;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class SpongeCommandManager implements CommandManager {

    private static final Parameter.Value<String> OPTIONAL_REMAINING_STRING =
            Parameter.remainingRawJoinedStrings().setKey("arguments").optional().build();

    private final Map<String, SpongeCommandMapping> commandMappings = new HashMap<>();
    private final Multimap<SpongeCommandMapping, String> inverseCommandMappings = HashMultimap.create();
    private final Multimap<PluginContainer, SpongeCommandMapping> pluginToCommandMap = HashMultimap.create();

    @Override
    @NonNull
    public CommandMapping registerAlias(
            @NonNull CommandRegistrar registrar,
            @NonNull PluginContainer container,
            @NonNull String primaryAlias,
            @NonNull String... secondaryAliases)
            throws CommandFailedRegistrationException {
        // Check it's been registered:
        if (primaryAlias.contains(" ") || Arrays.stream(secondaryAliases).anyMatch(x -> x.contains(" "))) {
                throw new CommandFailedRegistrationException("Aliases may not contain spaces.");
        }

        // We have a Sponge command, so let's start by checking to see what
        // we're going to register.
        String primaryAliasLowercase = primaryAlias.toLowerCase(Locale.ENGLISH);
        String namespacedAlias = container.getId() + ":" + primaryAlias.toLowerCase(Locale.ENGLISH);
        if (this.commandMappings.containsKey(namespacedAlias)) {
            // It's registered.
            throw new CommandFailedRegistrationException(
                    "The command alias " + primaryAlias + " has already been registered for this plugin");
        }

        Set<String> aliases = new HashSet<>();
        aliases.add(primaryAliasLowercase);
        aliases.add(namespacedAlias);
        for (String secondaryAlias : secondaryAliases) {
            aliases.add(secondaryAlias.toLowerCase(Locale.ENGLISH));
        }

        // Okay, what can we register?
        aliases.removeIf(this.commandMappings::containsKey);

        // We need to consider the configuration file - if there is an entry in there
        // then remove an alias if the command is not entitled to use it.
        SpongeImpl.getGlobalConfigAdapter().getConfig()
                .getCommands()
                .getAliases()
                .entrySet()
                .stream()
                .filter(x -> !x.getValue().equalsIgnoreCase(container.getId()))
                .filter(x -> aliases.contains(x.getKey()))
                .forEach(x -> aliases.remove(x.getKey()));

        if (aliases.isEmpty()) {
            // If the mapping is empty, throw an exception. Shouldn't happen, but you never know.
            throw new CommandFailedRegistrationException("No aliases could be registered for the supplied command.");
        }

        // Create the mapping
        SpongeCommandMapping mapping = new SpongeCommandMapping(
                primaryAlias,
                aliases,
                container,
                registrar
        );

        this.pluginToCommandMap.put(container, mapping);
        aliases.forEach(key -> {
            this.commandMappings.put(key, mapping);
            this.inverseCommandMappings.put(mapping, key);
        });
        return mapping;
    }

    // Sponge command
    @Override
    @NonNull
    public CommandMapping register(@NonNull PluginContainer container,
            @NonNull Command command,
            @NonNull String primaryAlias,
            @NonNull String... secondaryAliases) throws CommandFailedRegistrationException {
        CommandMapping mapping;
        if (command instanceof Command.Parameterized) {
            // send it to the Sponge Managed registrar
            mapping = SpongeManagedCommandRegistrar.INSTANCE.register(container, (Command.Parameterized) command, primaryAlias, secondaryAliases);
        } else {
            // send it to the Sponge Managed registrar
            mapping = SpongeRawCommandRegistrar.INSTANCE.register(container, command, primaryAlias, secondaryAliases);
        }

        return mapping;
    }

    @Override
    @NonNull
    public Optional<CommandMapping> unregister(@NonNull CommandMapping mapping) {
        if (!(mapping instanceof SpongeCommandMapping)) {
            throw new IllegalArgumentException("Mapping is not of type SpongeCommandMapping!");
        }

        SpongeCommandMapping spongeCommandMapping = (SpongeCommandMapping) mapping;

        // Cannot unregister Sponge or Minecraft commands
        if (isMinecraftOrSpongePluginContainer(mapping.getPlugin())) {
            return Optional.empty();
        }

        Collection<String> aliases = this.inverseCommandMappings.get(spongeCommandMapping);
        if (mapping.getAllAliases().containsAll(aliases)) {
            // Okay - the mapping checks out.
            this.inverseCommandMappings.removeAll(spongeCommandMapping);
            aliases.forEach(this.commandMappings::remove);
            this.pluginToCommandMap.remove(spongeCommandMapping.getPlugin(), spongeCommandMapping);

            // notify the registrar, which will do what it needs to do
            spongeCommandMapping.getRegistrar().unregister(mapping);
            return Optional.of(spongeCommandMapping);
        }

        return Optional.empty();
    }

    @Override
    @NonNull
    public Collection<CommandMapping> unregisterAll(@NonNull PluginContainer container) {
        Collection<SpongeCommandMapping> mappingsToRemove = this.pluginToCommandMap.get(container);
        ImmutableList.Builder<CommandMapping> commandMappingBuilder = ImmutableList.builder();
        for (CommandMapping toRemove : mappingsToRemove) {
            unregister(toRemove).ifPresent(commandMappingBuilder::add);
        }

        return commandMappingBuilder.build();
    }

    @Override
    @NonNull
    public Collection<PluginContainer> getPlugins() {
        return ImmutableSet.copyOf(this.pluginToCommandMap.keySet());
    }

    @Override
    public boolean isRegistered(@NonNull CommandMapping mapping) {
        Preconditions.checkArgument(mapping instanceof SpongeCommandMapping, "Mapping is not of type SpongeCommandMapping!");
        return this.inverseCommandMappings.containsKey(mapping);
    }

    @Override
    @NonNull
    public CommandResult process(@NonNull String arguments) throws CommandException {
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.addContext(EventContextKeys.COMMAND_STRING, arguments);
            String[] splitArg = arguments.split(" ", 2);
            String command = splitArg[0];
            String args = splitArg.length == 2 ? splitArg[1] : "";
            SpongeCommandMapping mapping = this.commandMappings.get(command.toLowerCase());
            if (mapping == null) {
                // no command.
                throw new CommandException(Text.of(TextColors.RED, "Unknown command. Type /help for a list of commands."));
            }
            return mapping.getRegistrar().process(CommandCause.of(frame.getCurrentCause()), mapping.getPrimaryAlias(), args);
        }
    }

    @Override
    @NonNull
    public <T extends Subject & MessageReceiver> CommandResult process(
            @NonNull T subjectReceiver,
            @NonNull String arguments) throws CommandException {
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.addContext(EventContextKeys.SUBJECT, subjectReceiver);
            frame.addContext(EventContextKeys.MESSAGE_CHANNEL, MessageChannel.to(subjectReceiver));
            return process(arguments);
        }
    }

    @Override
    @NonNull
    public CommandResult process(
            @NonNull Subject subject,
            @NonNull MessageChannel receiver,
            @NonNull String arguments) throws CommandException {
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.addContext(EventContextKeys.SUBJECT, subject);
            frame.addContext(EventContextKeys.MESSAGE_CHANNEL, receiver);
            return process(arguments);
        }
    }

    @Override
    @NonNull
    public List<String> suggest(@NonNull String arguments) {
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.addContext(EventContextKeys.COMMAND_STRING, arguments);
            String[] splitArg = arguments.split(" ", 2);
            String command = splitArg[0].toLowerCase();

            if (splitArg.length == 2) {
                // we have a subcommand, suggest on that if it exists, else
                // return nothing
                SpongeCommandMapping mapping = this.commandMappings.get(command);
                if (mapping == null) {
                    return Collections.emptyList();
                }

                return mapping.getRegistrar().suggestions(
                        CommandCause.of(frame.getCurrentCause()), mapping.getPrimaryAlias(), splitArg[1]);
            }

            return this.commandMappings.keySet()
                    .stream()
                    .filter(x -> x.startsWith(command))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    @NonNull
    public <T extends Subject & MessageReceiver> List<String> suggest(
            @NonNull T subjectReceiver,
            @NonNull String arguments) {
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.addContext(EventContextKeys.SUBJECT, subjectReceiver);
            frame.addContext(EventContextKeys.MESSAGE_CHANNEL, MessageChannel.to(subjectReceiver));
            return suggest(arguments);
        }
    }

    @Override
    @NonNull
    public List<String> suggest(
            @NonNull Subject subject,
            @NonNull MessageChannel receiver,
            @NonNull String arguments) {
        try (CauseStackManager.StackFrame frame = Sponge.getCauseStackManager().pushCauseFrame()) {
            frame.addContext(EventContextKeys.SUBJECT, subject);
            frame.addContext(EventContextKeys.MESSAGE_CHANNEL, receiver);
            return suggest(arguments);
        }
    }

    private boolean isMinecraftOrSpongePluginContainer(PluginContainer pluginContainer) {
        return !(SpongeImpl.getMinecraftPlugin() == pluginContainer || SpongeImpl.getSpongePlugin() == pluginContainer);
    }

}
