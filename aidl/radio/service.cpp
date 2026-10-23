/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <hidl/HidlTransportSupport.h>
#include <libradiocompat/CallbackManager.h>
#include <libradiocompat/RadioConfig.h>
#include <libradiocompat/RadioData.h>
#include <libradiocompat/RadioMessaging.h>
#include <libradiocompat/RadioModem.h>
#include <libradiocompat/RadioNetwork.h>
#include <libradiocompat/RadioSim.h>
#include <libradiocompat/RadioVoice.h>
#include <vendor/lge/hardware/lgdata/1.0/ILgData.h>

#include <memory>
#include <string>
#include <vector>

#include "LgDataClient.h"
#include "LgeRadioCompat.h"
#include "RadioAdapter.h"

namespace {

namespace compat = android::hardware::radio::compat;
namespace hidl = android::hardware::radio;

// Only services whose loss would leave the process
// unable to serve any slot are linked fatally, and only for slot 1.
class SlotDeathRecipient : public android::hardware::hidl_death_recipient {
  public:
    SlotDeathRecipient(std::string what, bool fatal)
        : mWhat(std::move(what)), mFatal(fatal) {}

    void serviceDied(uint64_t, const android::wp<android::hidl::base::V1_0::IBase>&) override {
        if (mFatal) {
            LOG(FATAL) << mWhat << " died; restarting lge-radio-aidl";
        }
        LOG(ERROR) << mWhat << " died; this slot is now unavailable";
    }

  private:
    std::string mWhat;
    bool mFatal;
};

std::vector<android::sp<SlotDeathRecipient>> gDeathRecipients;

void linkDeath(const android::sp<android::hidl::base::V1_0::IBase>& service,
               std::string what, bool fatal) {
    if (service == nullptr) {
        return;
    }
    auto recipient = android::sp<SlotDeathRecipient>::make(std::move(what), fatal);
    if (service->linkToDeath(recipient, 0).withDefault(false)) {
        gDeathRecipients.push_back(recipient);
    } else {
        LOG(WARNING) << "linkToDeath failed for " << what;
    }
}

std::vector<std::shared_ptr<ndk::ICInterface>> gServices;
std::vector<android::sp<lge::radio::LgDataClient>> gDataClients;

template <class T, class... Args>
bool publish(const std::string& slot, Args&&... args) {
    std::string name = std::string(T::descriptor) + "/" + slot;
    if (!AServiceManager_isDeclared(name.c_str())) {
        LOG(ERROR) << name << " not declared in VINTF manifest";
        return false;
    }
    auto service = ndk::SharedRefBase::make<T>(std::forward<Args>(args)...);
    if (service == nullptr) {
        LOG(ERROR) << "Failed to construct " << name;
        return false;
    }
    binder_status_t status = AServiceManager_addService(service->asBinder().get(), name.c_str());
    if (status != STATUS_OK) {
        LOG(ERROR) << "addService(" << name << ") failed: " << status;
        return false;
    }
    gServices.push_back(service);
    return true;
}

// Verify every AIDL radio interface for this slot is declared before we
// publish any of them, so slot bring-up is all-or-nothing.
bool precheckSlotDeclarations(const std::string& slot) {
    const char* descriptors[] = {
            compat::RadioData::descriptor,  compat::RadioMessaging::descriptor,
            compat::RadioModem::descriptor, compat::RadioNetwork::descriptor,
            compat::RadioSim::descriptor,   compat::RadioVoice::descriptor,
    };
    bool ok = true;
    for (const char* d : descriptors) {
        std::string name = std::string(d) + "/" + slot;
        if (!AServiceManager_isDeclared(name.c_str())) {
            LOG(ERROR) << name << " missing from VINTF manifest";
            ok = false;
        }
    }
    return ok;
}

// Bring up one slot. Never fatal. Returns true if the six AIDL services
// were published; the framework can then discover SIM insert/remove on
// this slot through the normal ICC status path.
bool bringUpSlot(int slot,
                 const android::sp<hidl::V1_5::IRadio>& real,
                 const android::sp<vendor::lge::hardware::radio::V2_0::ILgeRadio>& extension,
                 const android::sp<vendor::lge::hardware::lgdata::V1_0::ILgData>& dataService) {
    const std::string slotName = "slot" + std::to_string(slot);

    if (!precheckSlotDeclarations(slotName)) {
        LOG(ERROR) << "Slot " << slot << " is not fully declared; skipping";
        return false;
    }

    auto context = std::make_shared<compat::DriverContext>();
    auto adapter = std::make_shared<lge::radio::RadioAdapter>();

    // A null extension is legal; LgeRadioCompat will run as a passthrough.
    auto backend = android::sp<compat::LgeRadioCompat>::make(real, extension, context, adapter);
    auto callbacks = std::make_shared<compat::CallbackManager>(context, backend);
    backend->setCallbackManager(callbacks);

    if (!publish<compat::RadioData>(slotName, context, backend, callbacks) ||
        !publish<compat::RadioMessaging>(slotName, context, backend, callbacks) ||
        !publish<compat::RadioModem>(slotName, context, backend, callbacks) ||
        !publish<compat::RadioNetwork>(slotName, context, backend, callbacks) ||
        !publish<compat::RadioSim>(slotName, context, backend, callbacks) ||
        !publish<compat::RadioVoice>(slotName, context, backend, callbacks)) {
        LOG(ERROR) << "Publish failed for " << slotName;
        return false;
    }

    // LG QoS is optional. Its absence only disables bearer reporting here.
    if (dataService != nullptr) {
        auto client = android::sp<lge::radio::LgDataClient>::make(adapter);
        auto status = dataService->setCallback(slot - 1, client);
        if (status.isOk()) {
            gDataClients.push_back(client);
        } else {
            LOG(WARNING) << "ILgData::setCallback(" << (slot - 1) << ") failed for " << slotName
                         << "; LG QoS disabled on this slot";
        }
    }

    LOG(INFO) << "Slot " << slot << " brought up as " << slotName
              << (extension != nullptr ? " with LG extension" : " (passthrough only)");
    return true;
}

}  // namespace

int main() {
    android::base::InitLogging(nullptr, android::base::LogdLogger(android::base::RADIO));
    android::base::SetDefaultTag("lge-radio-aidl");

    android::hardware::configureRpcThreadpool(6, false);
    ABinderProcess_setThreadPoolMaxThreadCount(6);
    ABinderProcess_startThreadPool();

    // RadioConfig is shared across slots; without it nothing is useful.
    auto config = hidl::config::V1_1::IRadioConfig::getService();
    if (config == nullptr) {
        LOG(FATAL) << "IRadioConfig not available";
    }
    linkDeath(config, "IRadioConfig", /*fatal=*/true);
    if (!publish<compat::RadioConfig>("default", config)) {
        LOG(FATAL) << "Failed to publish RadioConfig";
    }

    // ILgData is optional.
    auto dataService = vendor::lge::hardware::lgdata::V1_0::ILgData::getService();
    if (dataService == nullptr) {
        LOG(WARNING) << "ILgData not available; LG QoS reporting disabled";
    } else {
        linkDeath(dataService, "ILgData", /*fatal=*/false);
    }

    int slots = android::base::GetIntProperty("ro.boot.vendor.lge.sim_num", 1);
    if (slots < 1) slots = 1;
    if (slots > 3) slots = 3;
    LOG(INFO) << "ro.boot.vendor.lge.sim_num=" << slots;

    bool slot1Up = false;
    for (int slot = 1; slot <= slots; ++slot) {
        const std::string name = "slot" + std::to_string(slot);

        auto real = hidl::V1_5::IRadio::getService(name);
        if (real == nullptr) {
            if (slot == 1) {
                LOG(FATAL) << "Missing V1_5::IRadio/slot1";
            }
            LOG(ERROR) << "Missing V1_5::IRadio/" << name << "; skipping slot";
            continue;
        }
        linkDeath(real, name + " IRadio", /*fatal=*/(slot == 1));

        auto extName = "lge_radio" + (slot == 1 ? std::string() : std::to_string(slot));
        auto extension = vendor::lge::hardware::radio::V2_0::ILgeRadio::getService(extName);
        if (extension == nullptr) {
            LOG(WARNING) << "Missing " << extName << " for slot " << slot
                         << "; LG signal reporting unavailable on this slot";
        } else {
            linkDeath(extension, extName, /*fatal=*/(slot == 1));
        }

        if (!bringUpSlot(slot, real, extension, dataService)) {
            if (slot == 1) {
                LOG(FATAL) << "Slot 1 bring-up failed";
            }
            LOG(ERROR) << "Slot " << slot << " bring-up failed; slot 1 unaffected";
            continue;
        }
        if (slot == 1) slot1Up = true;
    }

    if (!slot1Up) {
        LOG(FATAL) << "Slot 1 never came up";
    }

    LOG(INFO) << "LG AIDL radio ready: services=" << gServices.size()
              << " dataClients=" << gDataClients.size();
    ABinderProcess_joinThreadPool();
    return 1;
}
