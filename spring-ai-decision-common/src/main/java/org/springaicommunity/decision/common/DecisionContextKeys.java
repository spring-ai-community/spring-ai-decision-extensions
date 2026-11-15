/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.decision.common;

/**
 * Keys of the {@code ChatClient} response context that one decision advisor writes and
 * another reads. They live here so the advisors can cooperate without depending on each
 * other's module.
 *
 * @author Christian Tzolov
 */
public final class DecisionContextKeys {

	/**
	 * Set by the guardrail advisor on a turn it refused, holding the name of the outcome
	 * that refused it. It marks the response as the guardrail's own words rather than the
	 * model's answer, so an advisor further out, such as the self-refine advisor, does
	 * not judge a refusal or retry it.
	 */
	public static final String GUARDRAIL_OUTCOME = "decision.guardrail.outcome";

	private DecisionContextKeys() {
	}

}
