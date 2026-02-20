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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import datadog.trace.api.Trace;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import jakarta.validation.Valid;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Michael Isvy
 * @author Wick Dynex
 */
@Controller
class OwnerController {

	private static final Logger logger = LogManager.getLogger(OwnerController.class);

	private static final String VIEWS_OWNER_CREATE_OR_UPDATE_FORM = "owners/createOrUpdateOwnerForm";

	private final OwnerRepository owners;

	public OwnerController(OwnerRepository owners) {
		this.owners = owners;
		logger.debug("OwnerController instantiated");
	}

	@InitBinder
	public void setAllowedFields(WebDataBinder dataBinder) {
		logger.debug("Entering setAllowedFields() - disallowing 'id' field");
		dataBinder.setDisallowedFields("id");
		logger.debug("Exiting setAllowedFields()");
	}

	@ModelAttribute("owner")
	public Owner findOwner(@PathVariable(name = "ownerId", required = false) Integer ownerId) {
		logger.debug("Entering findOwner() - ownerId={}", ownerId);
		Owner owner = ownerId == null ? new Owner()
				: this.owners.findById(ownerId)
					.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId
							+ ". Please ensure the ID is correct " + "and the owner exists in the database."));
		logger.debug("Exiting findOwner() - resolved owner id={}", owner.getId());
		return owner;
	}

	@GetMapping("/owners/new")
	public String initCreationForm() {
		logger.debug("Entering initCreationForm()");
		String view = VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
		logger.debug("Exiting initCreationForm() - returning view={}", view);
		return view;
	}

	@PostMapping("/owners/new")
	public String processCreationForm(@Valid Owner owner, BindingResult result, RedirectAttributes redirectAttributes) {
		logger.debug("Entering processCreationForm() - lastName={}, errors={}",
				owner.getLastName(), result.hasErrors());
		if (result.hasErrors()) {
			redirectAttributes.addFlashAttribute("error", "There was an error in creating the owner.");
			logger.debug("Exiting processCreationForm() - validation errors, returning form view");
			return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
		}

		this.owners.save(owner);
		redirectAttributes.addFlashAttribute("message", "New Owner Created");
		logger.debug("Exiting processCreationForm() - created owner id={}", owner.getId());
		return "redirect:/owners/" + owner.getId();
	}

	@GetMapping("/owners/find")
	public String initFindForm() {
		logger.debug("Entering initFindForm()");
		logger.debug("Exiting initFindForm() - returning findOwners view");
		return "owners/findOwners";
	}

	/**
	 * Datadog Custom Span: The {@code @Trace} annotation creates a custom APM
	 * span named "owner.search" for this method. This enables Datadog to track
	 * owner search operations as distinct spans in the flame graph, providing
	 * visibility into search performance and how often searches yield zero,
	 * one, or multiple results. The manual child span "owner.db.search"
	 * wraps the database query to measure DB lookup time independently.
	 * @see <a href="https://docs.datadoghq.com/tracing/guide/instrument_custom_method/?tab=java">
	 *     Datadog: Instrument Custom Method</a>
	 */
	@Trace(operationName = "owner.search", resourceName = "OwnerController.processFindForm")
	@GetMapping("/owners")
	public String processFindForm(@RequestParam(defaultValue = "1") int page, Owner owner, BindingResult result,
			Model model) {
		logger.debug("Entering processFindForm() - page={}, lastName={}", page, owner.getLastName());
		// allow parameterless GET request for /owners to return all records
		String lastName = owner.getLastName();
		if (lastName == null) {
			lastName = ""; // empty string signifies broadest possible search
		}

		// Custom manual span wrapping the database search operation.
		// This creates a child span under the @Trace span, isolating
		// database query time from the rest of the request processing.
		Tracer tracer = GlobalTracer.get();
		Span dbSpan = tracer.buildSpan("owner.db.search")
			.withTag("owner.lastName", lastName)
			.withTag("search.page", page)
			.start();
		Page<Owner> ownersResults;
		try (Scope scope = tracer.activateSpan(dbSpan)) {
			ownersResults = findPaginatedForOwnersLastName(page, lastName);
			dbSpan.setTag("search.results.total",
					ownersResults.getTotalElements());
		}
		finally {
			dbSpan.finish();
		}

		if (ownersResults.isEmpty()) {
			// no owners found
			result.rejectValue("lastName", "notFound", "not found");
			logger.debug("Exiting processFindForm() - no owners found for lastName={}", lastName);
			return "owners/findOwners";
		}

		if (ownersResults.getTotalElements() == 1) {
			// 1 owner found
			owner = ownersResults.iterator().next();
			logger.debug("Exiting processFindForm() - single owner found, redirecting to id={}", owner.getId());
			return "redirect:/owners/" + owner.getId();
		}

		// multiple owners found
		String view = addPaginationModel(page, model, ownersResults);
		logger.debug("Exiting processFindForm() - found {} owners, paginated view",
				ownersResults.getTotalElements());
		return view;
	}

	private String addPaginationModel(int page, Model model, Page<Owner> paginated) {
		logger.debug("Entering addPaginationModel() - page={}, totalElements={}", page, paginated.getTotalElements());
		List<Owner> listOwners = paginated.getContent();
		model.addAttribute("currentPage", page);
		model.addAttribute("totalPages", paginated.getTotalPages());
		model.addAttribute("totalItems", paginated.getTotalElements());
		model.addAttribute("listOwners", listOwners);
		logger.debug("Exiting addPaginationModel() - totalPages={}, listSize={}",
				paginated.getTotalPages(), listOwners.size());
		return "owners/ownersList";
	}

	private Page<Owner> findPaginatedForOwnersLastName(int page, String lastname) {
		logger.debug("Entering findPaginatedForOwnersLastName() - page={}, lastname={}", page, lastname);
		int pageSize = 5;
		Pageable pageable = PageRequest.of(page - 1, pageSize);
		Page<Owner> results = owners.findByLastNameStartingWith(lastname, pageable);
		logger.debug("Exiting findPaginatedForOwnersLastName() - found {} results", results.getTotalElements());
		return results;
	}

	@GetMapping("/owners/{ownerId}/edit")
	public String initUpdateOwnerForm() {
		logger.debug("Entering initUpdateOwnerForm()");
		logger.debug("Exiting initUpdateOwnerForm() - returning edit form view");
		return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
	}

	@PostMapping("/owners/{ownerId}/edit")
	public String processUpdateOwnerForm(@Valid Owner owner, BindingResult result, @PathVariable("ownerId") int ownerId,
			RedirectAttributes redirectAttributes) {
		logger.debug("Entering processUpdateOwnerForm() - ownerId={}, hasErrors={}", ownerId, result.hasErrors());
		if (result.hasErrors()) {
			redirectAttributes.addFlashAttribute("error", "There was an error in updating the owner.");
			logger.debug("Exiting processUpdateOwnerForm() - validation errors, returning form view");
			return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
		}

		if (!Objects.equals(owner.getId(), ownerId)) {
			result.rejectValue("id", "mismatch", "The owner ID in the form does not match the URL.");
			redirectAttributes.addFlashAttribute("error", "Owner ID mismatch. Please try again.");
			logger.debug("Exiting processUpdateOwnerForm() - ID mismatch: form={}, url={}", owner.getId(), ownerId);
			return "redirect:/owners/{ownerId}/edit";
		}

		owner.setId(ownerId);
		this.owners.save(owner);
		redirectAttributes.addFlashAttribute("message", "Owner Values Updated");
		logger.debug("Exiting processUpdateOwnerForm() - updated owner id={}", ownerId);
		return "redirect:/owners/{ownerId}";
	}

	/**
	 * Custom handler for displaying an owner.
	 * @param ownerId the ID of the owner to display
	 * @return a ModelMap with the model attributes for the view
	 */
	@GetMapping("/owners/{ownerId}")
	public ModelAndView showOwner(@PathVariable("ownerId") int ownerId) {
		logger.debug("Entering showOwner() - ownerId={}", ownerId);
		ModelAndView mav = new ModelAndView("owners/ownerDetails");
		Optional<Owner> optionalOwner = this.owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));
		mav.addObject(owner);
		logger.debug("Exiting showOwner() - displaying owner id={}, lastName={}", ownerId, owner.getLastName());
		return mav;
	}

}
