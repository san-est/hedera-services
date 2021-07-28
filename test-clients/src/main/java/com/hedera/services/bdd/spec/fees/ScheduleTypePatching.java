package com.hedera.services.bdd.spec.fees;

/*-
 * ‌
 * Hedera Services Test Clients
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.pricing.RequiredPriceTypes;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TransactionFeeSchedule;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;

/**
 * Helper class to avoid problems when restarting from a saved state from the last release, whose
 * fee schedules are missing one or more price types that are required in the current release.
 */
public class ScheduleTypePatching {
	public FeeSchedule withPatchedTypesIfNecessary(FeeSchedule possiblyUntypedSchedule) {
		final var usableSchedule = FeeSchedule.newBuilder();
		for (var tfs : possiblyUntypedSchedule.getTransactionFeeScheduleList()) {
			final var usableTfs = TransactionFeeSchedule.newBuilder();
			final var fn = tfs.getHederaFunctionality();
			usableTfs.mergeFrom(tfs);
			final Set<SubType> requiredTypes = RequiredPriceTypes.requiredTypesFor(fn);
			ensurePatchedFeeScheduleHasRequiredTypes(tfs, usableTfs, requiredTypes);
			usableSchedule.addTransactionFeeSchedule(usableTfs);
		}
		return usableSchedule.build();
	}

	private void ensurePatchedFeeScheduleHasRequiredTypes(
			TransactionFeeSchedule origTfs,
			TransactionFeeSchedule.Builder patchedTfs,
			Set<SubType> requiredTypes
	) {
		/* The deprecated prices are the final fallback; if even they are not set, the function will be free */
		final var oldDefaultPrices = origTfs.getFeeData();
		FeeData explicitDefaultPrices = null;

		/* First determine what types are already present; and what default prices to use, if any */
		final List<SubType> listedTypes = new ArrayList<>();
		for (var typedPrices : origTfs.getFeesList()) {
			final var type = typedPrices.getSubType();
			listedTypes.add(type);
			if (type == DEFAULT) {
				explicitDefaultPrices = typedPrices;
			}
		}

		final Set<SubType> presentTypes = listedTypes.isEmpty()
				? EnumSet.noneOf(SubType.class)
				: EnumSet.copyOf(listedTypes);
		for (var type : requiredTypes) {
			if (!presentTypes.contains(type)) {
				if (explicitDefaultPrices != null) {
					patchedTfs.addFees(explicitDefaultPrices.toBuilder().setSubType(type).build());
				} else {
					patchedTfs.addFees(oldDefaultPrices.toBuilder().setSubType(type).build());
				}
			}
		}
	}
}
