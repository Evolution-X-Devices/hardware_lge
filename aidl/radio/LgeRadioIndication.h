/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <libradiocompat/RadioIndication.h>

namespace lge::radio {
class RadioAdapter;
}  // namespace lge::radio

namespace android::hardware::radio::compat {

class LgeRadioIndication : public RadioIndication {
  public:
    LgeRadioIndication(std::shared_ptr<DriverContext> context,
                       std::shared_ptr<::lge::radio::RadioAdapter> adapter);

  private:
    Return<void> dataCallListChanged_1_5(V1_0::RadioIndicationType type,
                                         const hidl_vec<V1_5::SetupDataCallResult>& calls) override;

    Return<void> currentSignalStrength_1_4(V1_0::RadioIndicationType type,
                                           const V1_4::SignalStrength& signal) override;

    Return<void> radioStateChanged(V1_0::RadioIndicationType type, V1_0::RadioState state) override;

    Return<void> simStatusChanged(V1_0::RadioIndicationType type) override;

    std::shared_ptr<::lge::radio::RadioAdapter> mAdapter;
};

}  // namespace android::hardware::radio::compat
