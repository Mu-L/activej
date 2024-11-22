/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.trigger;

import io.activej.common.builder.AbstractBuilder;
import io.activej.common.time.CurrentTimeProvider;
import io.activej.jmx.api.ConcurrentJmxBean;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.activej.common.collection.CollectionUtils.difference;
import static io.activej.common.collection.CollectionUtils.last;
import static io.activej.jmx.stats.MBeanFormat.formatListAsMultilineString;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public final class Triggers implements ConcurrentJmxBean {
	public static final Duration CACHE_TIMEOUT = Duration.ofSeconds(1);

	private final List<Trigger> triggers = new ArrayList<>();
	CurrentTimeProvider now = CurrentTimeProvider.ofSystem();

	private Triggers() {
	}

	public record TriggerKey(String component, String name) {}

	private final Map<Trigger, TriggerResult> suppressedResults = new LinkedHashMap<>();
	private final Map<Trigger, TriggerResult> cachedResults = new LinkedHashMap<>();
	private Map<Trigger, TriggerWithResult> maxSeverityResults = new LinkedHashMap<>();
	private long cachedTimestamp;

	private final Predicate<TriggerWithResult> isNotSuppressed = triggerWithResult -> {
		Trigger trigger = triggerWithResult.trigger;
		if (suppressedResults.containsKey(trigger)) {
			TriggerResult suppressedTriggerResult = suppressedResults.get(trigger);
			TriggerResult triggerResult = triggerWithResult.triggerResult();

			return
				triggerResult.getCount() > suppressedTriggerResult.getCount() ||
				triggerResult.getTimestamp() > suppressedTriggerResult.getTimestamp();
		}
		return true;
	};

	public static Triggers create() {
		return builder().build();
	}

	public static Builder builder() {
		return new Triggers().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, Triggers> {
		private Builder() {}

		public Builder withTrigger(Trigger trigger) {
			checkNotBuilt(this);
			addTrigger(trigger);
			return this;
		}

		@SuppressWarnings("UnusedReturnValue")
		public Builder withTrigger(Severity severity, String component, String name, Supplier<TriggerResult> triggerFunction) {
			checkNotBuilt(this);
			return withTrigger(Trigger.of(severity, component, name, triggerFunction));
		}

		@Override
		protected Triggers doBuild() {
			return Triggers.this;
		}
	}

	public synchronized void addTrigger(Trigger trigger) {
		triggers.add(trigger);
	}

	public synchronized void addTrigger(Severity severity, String component, String name, Supplier<TriggerResult> triggerFunction) {
		triggers.add(Trigger.of(severity, component, name, triggerFunction));
	}

	private void refresh() {
		long currentTime = now.currentTimeMillis();
		if (cachedTimestamp + CACHE_TIMEOUT.toMillis() < currentTime) {
			cachedTimestamp = currentTime;

			Map<Trigger, TriggerResult> newResults = new HashMap<>();
			for (Trigger trigger : triggers) {
				TriggerResult newResult;
				try {
					newResult = trigger.getTriggerFunction().get();
				} catch (Exception e) {
					newResult = TriggerResult.ofError(e);
				}
				if (newResult != null && newResult.isPresent()) {
					newResults.put(trigger, newResult);
				}
			}
			for (Trigger trigger : new HashSet<>(difference(cachedResults.keySet(), newResults.keySet()))) {
				cachedResults.remove(trigger);
				suppressedResults.remove(trigger);
			}
			for (Map.Entry<Trigger, TriggerResult> entry : newResults.entrySet()) {
				TriggerResult newResult = entry.getValue();
				if (!newResult.hasTimestamp()) {
					TriggerResult oldResult = cachedResults.get(entry.getKey());
					newResult = TriggerResult.create(
						oldResult == null ? currentTime : oldResult.getTimestamp(),
						newResult.getThrowable(),
						newResult.getValue());
				}
				cachedResults.put(entry.getKey(), newResult.withCount(0));
			}
			for (Map.Entry<Trigger, TriggerResult> entry : newResults.entrySet()) {
				TriggerResult oldResult = cachedResults.get(entry.getKey());
				cachedResults.put(entry.getKey(), oldResult.withCount(oldResult.getCount() + entry.getValue().getCount()));
			}
			maxSeverityResults = new HashMap<>(cachedResults.size());
			for (Map.Entry<Trigger, TriggerResult> entry : cachedResults.entrySet()) {
				Trigger trigger = entry.getKey();
				TriggerResult triggerResult = entry.getValue();

				TriggerWithResult oldTriggerWithResult = maxSeverityResults.get(trigger);
				if (oldTriggerWithResult == null ||
					oldTriggerWithResult.trigger().getSeverity().ordinal() < trigger.getSeverity().ordinal() ||
					oldTriggerWithResult.trigger().getSeverity() == trigger.getSeverity() &&
					oldTriggerWithResult.triggerResult().getTimestamp() > triggerResult.getTimestamp()
				) {
					maxSeverityResults.put(trigger, new TriggerWithResult(trigger, triggerResult
						.withCount(triggerResult.getCount())));
				} else {
					maxSeverityResults.put(trigger, new TriggerWithResult(oldTriggerWithResult.trigger(), oldTriggerWithResult.triggerResult()
						.withCount(triggerResult.getCount())));
				}
			}

		}
	}

	public record TriggerWithResult(Trigger trigger, TriggerResult triggerResult) {
		@Override
		public String toString() {
			return trigger + " :: " + triggerResult;
		}
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResultsDebug() {
		return getResultsBySeverity(Severity.DEBUG);
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResultsInformation() {
		return getResultsBySeverity(Severity.INFORMATION);
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResultsWarning() {
		return getResultsBySeverity(Severity.WARNING);
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResultsAverage() {
		return getResultsBySeverity(Severity.AVERAGE);
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResultsHigh() {
		return getResultsBySeverity(Severity.HIGH);
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResultsDisaster() {
		return getResultsBySeverity(Severity.DISASTER);
	}

	private List<TriggerWithResult> getResultsBySeverity(@Nullable Severity severity) {
		refresh();
		return maxSeverityResults.values().stream()
			.filter(isNotSuppressed)
			.filter(entry -> entry.trigger().getSeverity() == severity)
			.sorted(comparing(item -> item.triggerResult().getTimestamp()))
			.collect(Collectors.groupingBy(o -> new TriggerKey(o.trigger().getComponent(), o.trigger().getName())))
			.values()
			.stream()
			.flatMap(list -> list.stream()
				.filter(trigger -> trigger.trigger().getSeverity() == last(list).trigger().getSeverity()))
			.collect(Collectors.toList());
	}

	@JmxAttribute
	public synchronized List<TriggerWithResult> getResults() {
		refresh();
		return maxSeverityResults.values().stream()
			.filter(isNotSuppressed)
			.sorted(Comparator.<TriggerWithResult, Severity>comparing(item -> item.trigger().getSeverity())
				.thenComparing(item -> item.triggerResult().getTimestamp()))
			.collect(Collectors.groupingBy(o -> new TriggerKey(o.trigger().getComponent(), o.trigger().getName())))
			.values()
			.stream()
			.flatMap(list -> list.stream()
				.filter(trigger -> trigger.trigger().getSeverity() == last(list).trigger().getSeverity()))
			.collect(Collectors.toList());
	}

	@JmxAttribute
	public synchronized String getMultilineSuppressedResults() {
		return formatListAsMultilineString(new ArrayList<>(suppressedResults.keySet()));
	}

	@JmxAttribute
	public synchronized String getMultilineResultsDebug() {
		return formatListAsMultilineString(getResultsBySeverity(Severity.DEBUG));
	}

	@JmxAttribute
	public synchronized String getMultilineResultsInformation() {
		return formatListAsMultilineString(getResultsBySeverity(Severity.INFORMATION));
	}

	@JmxAttribute
	public synchronized String getMultilineResultsWarning() {
		return formatListAsMultilineString(getResultsBySeverity(Severity.WARNING));
	}

	@JmxAttribute
	public synchronized String getMultilineResultsAverage() {
		return formatListAsMultilineString(getResultsBySeverity(Severity.AVERAGE));
	}

	@JmxAttribute
	public synchronized String getMultilineResultsHigh() {
		return formatListAsMultilineString(getResultsBySeverity(Severity.HIGH));
	}

	@JmxAttribute
	public synchronized String getMultilineResultsDisaster() {
		return formatListAsMultilineString(getResultsBySeverity(Severity.DISASTER));
	}

	@JmxAttribute
	public synchronized String getMultilineResults() {
		return formatListAsMultilineString(getResults());
	}

	@JmxAttribute
	public synchronized @Nullable Severity getMaxSeverity() {
		refresh();
		return maxSeverityResults.values().stream()
			.filter(isNotSuppressed)
			.max(comparing(entry -> entry.trigger().getSeverity()))
			.map(entry -> entry.trigger().getSeverity())
			.orElse(null);
	}

	@JmxAttribute
	public synchronized @Nullable String getMaxSeverityResult() {
		refresh();
		return maxSeverityResults.values().stream()
			.filter(isNotSuppressed)
			.max(comparing(entry -> entry.trigger().getSeverity()))
			.map(Object::toString)
			.orElse(null);
	}

	@JmxAttribute
	public synchronized List<String> getTriggers() {
		return triggers.stream()
			.sorted(comparing(Trigger::getSeverity)
				.reversed()
				.thenComparing(Trigger::getComponent)
				.thenComparing(Trigger::getName))
			.map(t -> t.getSeverity() + " : " + t.getComponent() + " : " + t.getName())
			.distinct()
			.collect(toList());
	}

	@JmxAttribute
	public synchronized List<String> getTriggerNames() {
		return triggers.stream()
			.sorted(comparing(Trigger::getComponent)
				.thenComparing(Trigger::getName))
			.map(t -> t.getComponent() + " : " + t.getName())
			.distinct()
			.collect(toList());
	}

	@JmxAttribute
	public synchronized String getTriggerComponents() {
		return triggers.stream()
			.sorted(comparing(Trigger::getComponent))
			.map(Trigger::getComponent)
			.distinct()
			.collect(joining(", "));
	}

	@JmxOperation
	public synchronized void suppressAllTriggers() {
		suppressBy(trigger -> true);
	}

	@JmxOperation
	public synchronized void suppressTriggerByName(String name) {
		suppressBy(trigger -> trigger.getName().equals(name));
	}

	@JmxOperation
	public synchronized void suppressTriggerByComponent(String component) {
		suppressBy(trigger -> trigger.getComponent().equals(component));
	}

	@JmxOperation
	public synchronized void suppressTriggerBySeverity(String severity) {
		suppressBy(trigger -> trigger.getSeverity().name().equalsIgnoreCase(severity));
	}

	/**
	 * @param signature Trigger signature in a form of <i>"Severity:Component:Name"</i>
	 */
	@JmxOperation
	public synchronized void suppressTriggersBySignature(String signature) {
		String[] values = signature.split(":");
		if (values.length != 3) {
			return;
		}

		suppressBy(trigger ->
			trigger.getSeverity().name().equalsIgnoreCase(values[0].trim()) &&
			trigger.getComponent().equals(values[1].trim()) &&
			trigger.getName().equals(values[2].trim()));
	}

	private void suppressBy(Predicate<Trigger> condition) {
		refresh();
		cachedResults.keySet().stream()
			.filter(condition)
			.forEach(trigger -> suppressedResults.put(trigger, cachedResults.get(trigger)));
	}

	@Override
	public String toString() {
		return getTriggerComponents();
	}
}
