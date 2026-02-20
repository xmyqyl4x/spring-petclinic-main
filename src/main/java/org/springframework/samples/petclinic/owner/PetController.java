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

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.validation.Valid;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Wick Dynex
 */
@Controller
@RequestMapping("/owners/{ownerId}")
class PetController {

	private static final Logger logger = LogManager.getLogger(PetController.class);

	private static final String VIEWS_PETS_CREATE_OR_UPDATE_FORM = "pets/createOrUpdatePetForm";

	private final OwnerRepository owners;

	private final PetTypeRepository types;

	public PetController(OwnerRepository owners, PetTypeRepository types) {
		this.owners = owners;
		this.types = types;
		logger.debug("PetController instantiated");
	}

	@ModelAttribute("types")
	public Collection<PetType> populatePetTypes() {
		logger.debug("Entering populatePetTypes()");
		Collection<PetType> petTypes = this.types.findPetTypes();
		logger.debug("Exiting populatePetTypes() - found {} pet types", petTypes.size());
		return petTypes;
	}

	@ModelAttribute("owner")
	public Owner findOwner(@PathVariable("ownerId") int ownerId) {
		logger.debug("Entering findOwner() - ownerId={}", ownerId);
		Optional<Owner> optionalOwner = this.owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));
		logger.debug("Exiting findOwner() - resolved owner id={}, lastName={}", owner.getId(), owner.getLastName());
		return owner;
	}

	@ModelAttribute("pet")
	public Pet findPet(@PathVariable("ownerId") int ownerId,
			@PathVariable(name = "petId", required = false) Integer petId) {
		logger.debug("Entering findPet() - ownerId={}, petId={}", ownerId, petId);

		if (petId == null) {
			logger.debug("Exiting findPet() - petId is null, returning new Pet");
			return new Pet();
		}

		Optional<Owner> optionalOwner = this.owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));
		Pet pet = owner.getPet(petId);
		logger.debug("Exiting findPet() - found pet name={}", pet != null ? pet.getName() : "null");
		return pet;
	}

	@InitBinder("owner")
	public void initOwnerBinder(WebDataBinder dataBinder) {
		logger.debug("Entering initOwnerBinder() - disallowing 'id' field");
		dataBinder.setDisallowedFields("id");
		logger.debug("Exiting initOwnerBinder()");
	}

	@InitBinder("pet")
	public void initPetBinder(WebDataBinder dataBinder) {
		logger.debug("Entering initPetBinder() - setting PetValidator");
		dataBinder.setValidator(new PetValidator());
		logger.debug("Exiting initPetBinder()");
	}

	@GetMapping("/pets/new")
	public String initCreationForm(Owner owner, ModelMap model) {
		logger.debug("Entering initCreationForm() - owner id={}", owner.getId());
		Pet pet = new Pet();
		owner.addPet(pet);
		logger.debug("Exiting initCreationForm() - returning pet creation form view");
		return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
	}

	/**
	 * Datadog Custom Tags: Adds custom tags to the active APM span to enrich
	 * trace data with pet-specific business context. Tags like "pet.name",
	 * "pet.type", "owner.id", and "validation.passed" allow filtering and
	 * grouping in Datadog APM dashboards — for example, to see which pet
	 * types are created most often or how frequently validation fails.
	 * @see <a href=
	 *     "https://docs.datadoghq.com/tracing/trace_collection/custom_instrumentation/server-side/">
	 *     Datadog: Add Custom Tags</a>
	 */
	@PostMapping("/pets/new")
	public String processCreationForm(Owner owner, @Valid Pet pet, BindingResult result,
			RedirectAttributes redirectAttributes) {
		logger.debug("Entering processCreationForm() - owner id={}, pet name={}", owner.getId(), pet.getName());

		// Add custom tags to the active Datadog APM span.
		// These tags enrich the trace with business-level context so you
		// can filter/group in Datadog by pet type, owner, or outcome.
		Span span = GlobalTracer.get().activeSpan();
		if (span != null) {
			span.setTag("pet.name", pet.getName() != null ? pet.getName() : "");
			span.setTag("owner.id", owner.getId() != null ? owner.getId() : 0);
			if (pet.getType() != null) {
				span.setTag("pet.type", pet.getType().getName());
			}
		}

		if (StringUtils.hasText(pet.getName()) && pet.isNew() && owner.getPet(pet.getName(), true) != null)
			result.rejectValue("name", "duplicate", "already exists");

		LocalDate currentDate = LocalDate.now();
		if (pet.getBirthDate() != null && pet.getBirthDate().isAfter(currentDate)) {
			result.rejectValue("birthDate", "typeMismatch.birthDate");
		}

		if (result.hasErrors()) {
			if (span != null) {
				span.setTag("validation.passed", false);
				span.setTag("validation.error_count", result.getErrorCount());
			}
			logger.debug("Exiting processCreationForm() - validation errors, returning form view");
			return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
		}

		if (span != null) {
			span.setTag("validation.passed", true);
		}
		owner.addPet(pet);
		this.owners.save(owner);
		redirectAttributes.addFlashAttribute("message", "New Pet has been Added");
		logger.debug("Exiting processCreationForm() - pet name={} for owner id={}",
				pet.getName(), owner.getId());
		return "redirect:/owners/{ownerId}";
	}

	@GetMapping("/pets/{petId}/edit")
	public String initUpdateForm() {
		logger.debug("Entering initUpdateForm()");
		logger.debug("Exiting initUpdateForm() - returning pet edit form view");
		return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
	}

	@PostMapping("/pets/{petId}/edit")
	public String processUpdateForm(Owner owner, @Valid Pet pet, BindingResult result,
			RedirectAttributes redirectAttributes) {
		logger.debug("Entering processUpdateForm() - owner={}, pet={}, name={}",
				owner.getId(), pet.getId(), pet.getName());

		String petName = pet.getName();

		// checking if the pet name already exists for the owner
		if (StringUtils.hasText(petName)) {
			Pet existingPet = owner.getPet(petName, false);
			if (existingPet != null && !Objects.equals(existingPet.getId(), pet.getId())) {
				result.rejectValue("name", "duplicate", "already exists");
			}
		}

		LocalDate currentDate = LocalDate.now();
		if (pet.getBirthDate() != null && pet.getBirthDate().isAfter(currentDate)) {
			result.rejectValue("birthDate", "typeMismatch.birthDate");
		}

		if (result.hasErrors()) {
			logger.debug("Exiting processUpdateForm() - validation errors, returning form view");
			return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
		}

		updatePetDetails(owner, pet);
		redirectAttributes.addFlashAttribute("message", "Pet details has been edited");
		logger.debug("Exiting processUpdateForm() - updated pet id={} for owner id={}", pet.getId(), owner.getId());
		return "redirect:/owners/{ownerId}";
	}

	/**
	 * Updates the pet details if it exists or adds a new pet to the owner.
	 * @param owner The owner of the pet
	 * @param pet The pet with updated details
	 */
	private void updatePetDetails(Owner owner, Pet pet) {
		logger.debug("Entering updatePetDetails() - owner id={}, pet id={}", owner.getId(), pet.getId());
		Integer id = pet.getId();
		Assert.state(id != null, "'pet.getId()' must not be null");
		Pet existingPet = owner.getPet(id);
		if (existingPet != null) {
			// Update existing pet's properties
			existingPet.setName(pet.getName());
			existingPet.setBirthDate(pet.getBirthDate());
			existingPet.setType(pet.getType());
			logger.debug("Exiting updatePetDetails() - updated existing pet id={}", id);
		}
		else {
			owner.addPet(pet);
			logger.debug("Exiting updatePetDetails() - added new pet to owner id={}", owner.getId());
		}
		this.owners.save(owner);
	}

}
