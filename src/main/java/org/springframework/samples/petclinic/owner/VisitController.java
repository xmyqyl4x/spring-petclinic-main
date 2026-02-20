/*
 * Copyright 2012-2025 the original author or authors.
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
package org.springframework.samples.petclinic.owner;

import java.util.Map;
import java.util.Optional;

import datadog.trace.api.Trace;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Michael Isvy
 * @author Dave Syer
 * @author Wick Dynex
 */
@Controller
class VisitController {

	private static final Logger logger = LogManager.getLogger(VisitController.class);

	private final OwnerRepository owners;

	public VisitController(OwnerRepository owners) {
		this.owners = owners;
		logger.debug("VisitController instantiated");
	}

	@InitBinder
	public void setAllowedFields(WebDataBinder dataBinder) {
		logger.debug("Entering setAllowedFields() - disallowing 'id' field");
		dataBinder.setDisallowedFields("id");
		logger.debug("Exiting setAllowedFields()");
	}

	/**
	 * Called before each and every @RequestMapping annotated method. 2 goals: - Make sure
	 * we always have fresh data - Since we do not use the session scope, make sure that
	 * Pet object always has an id (Even though id is not part of the form fields)
	 * @param petId
	 * @return Pet
	 */
	@ModelAttribute("visit")
	public Visit loadPetWithVisit(@PathVariable("ownerId") int ownerId, @PathVariable("petId") int petId,
			Map<String, Object> model) {
		logger.debug("Entering loadPetWithVisit() - ownerId={}, petId={}", ownerId, petId);
		Optional<Owner> optionalOwner = owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));

		Pet pet = owner.getPet(petId);
		if (pet == null) {
			throw new IllegalArgumentException(
					"Pet with id " + petId + " not found for owner with id " + ownerId + ".");
		}
		model.put("pet", pet);
		model.put("owner", owner);

		Visit visit = new Visit();
		pet.addVisit(visit);
		logger.debug("Exiting loadPetWithVisit() - pet={}, owner={}",
				pet.getName(), owner.getLastName());
		return visit;
	}

	// Spring MVC calls method loadPetWithVisit(...) before initNewVisitForm is
	// called
	@GetMapping("/owners/{ownerId}/pets/{petId}/visits/new")
	public String initNewVisitForm() {
		logger.debug("Entering initNewVisitForm()");
		logger.debug("Exiting initNewVisitForm() - returning visit form view");
		return "pets/createOrUpdateVisitForm";
	}

	/**
	 * Datadog Custom Metrics from Spans: Adds numeric and dimensional tags to
	 * the active span that Datadog can use to generate span-based custom
	 * metrics. In the Datadog UI (APM > Setup &amp; Configuration > Generate
	 * Metrics), you can create metrics such as:
	 * <ul>
	 *   <li>Count of visits grouped by {@code visit.pet.id} to track
	 *       per-pet visit frequency</li>
	 *   <li>Distribution on {@code visit.booking.duration_ms} to monitor
	 *       how long the booking operation takes</li>
	 *   <li>Count filtered by {@code visit.outcome=booked} vs
	 *       {@code visit.outcome=validation_error} to measure success
	 *       rate</li>
	 * </ul>
	 * The {@code @Trace} annotation ensures this method has its own span
	 * so the tags are always attached to a predictable span name.
	 * @see <a href="https://docs.datadoghq.com/tracing/trace_pipeline/generate_metrics/">
	 *     Datadog: Generate Custom Metrics from Spans</a>
	 */
	@Trace(operationName = "visit.booking",
			resourceName = "VisitController.processNewVisitForm")
	// Spring MVC calls method loadPetWithVisit(...) before processNewVisitForm is
	// called
	@PostMapping("/owners/{ownerId}/pets/{petId}/visits/new")
	public String processNewVisitForm(@ModelAttribute Owner owner, @PathVariable int petId, @Valid Visit visit,
			BindingResult result, RedirectAttributes redirectAttributes) {
		long startTime = System.currentTimeMillis();
		logger.debug("Entering processNewVisitForm() - ownerId={}, petId={}, errors={}",
				owner.getId(), petId, result.hasErrors());

		// Attach numeric/dimensional tags to the span that Datadog can
		// aggregate into custom metrics via Generate Metrics.
		Span span = GlobalTracer.get().activeSpan();
		if (span != null) {
			span.setTag("visit.owner.id", owner.getId() != null ? owner.getId() : 0);
			span.setTag("visit.pet.id", petId);
		}

		if (result.hasErrors()) {
			if (span != null) {
				span.setTag("visit.outcome", "validation_error");
				span.setTag("visit.validation.error_count", result.getErrorCount());
			}
			logger.debug("Exiting processNewVisitForm() - validation errors, returning form view");
			return "pets/createOrUpdateVisitForm";
		}

		owner.addVisit(petId, visit);
		this.owners.save(owner);
		redirectAttributes.addFlashAttribute("message", "Your visit has been booked");

		if (span != null) {
			span.setTag("visit.outcome", "booked");
			span.setTag("visit.booking.duration_ms",
					System.currentTimeMillis() - startTime);
		}
		logger.debug("Exiting processNewVisitForm() - visit booked for pet id={}, owner id={}", petId, owner.getId());
		return "redirect:/owners/{ownerId}";
	}

}
