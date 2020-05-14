/*
 * Copyright (C) 2019 The Android Open Source Project
 *
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
 */

#include "HalCamera.h"
#include "VirtualCamera.h"
#include "Enumerator.h"

#include <android-base/logging.h>
#include <ui/GraphicBufferAllocator.h>
#include <ui/GraphicBufferMapper.h>


namespace android {
namespace automotive {
namespace evs {
namespace V1_1 {
namespace implementation {


// TODO(changyeon):
// We need to hook up death monitoring to detect stream death so we can attempt a reconnect


sp<VirtualCamera> HalCamera::makeVirtualCamera() {

    // Create the client camera interface object
    std::vector<sp<HalCamera>> sourceCameras;
    sourceCameras.reserve(1);
    sourceCameras.emplace_back(this);
    sp<VirtualCamera> client = new VirtualCamera(sourceCameras);
    if (client == nullptr) {
        LOG(ERROR) << "Failed to create client camera object";
        return nullptr;
    }

    if (!ownVirtualCamera(client)) {
        LOG(ERROR) << "Failed to own a client camera object";
        client = nullptr;
    }

    return client;
}


bool HalCamera::ownVirtualCamera(sp<VirtualCamera> virtualCamera) {

    if (virtualCamera == nullptr) {
        LOG(ERROR) << "Failed to create virtualCamera camera object";
        return false;
    }

    // Make sure we have enough buffers available for all our clients
    if (!changeFramesInFlight(virtualCamera->getAllowedBuffers())) {
        // Gah!  We couldn't get enough buffers, so we can't support this virtualCamera
        // Null the pointer, dropping our reference, thus destroying the virtualCamera object
        return false;
    }

    if (mSyncSupported) {
        // Create a timeline
        std::lock_guard<std::mutex> lock(mFrameMutex);
        auto timeline = make_unique<UniqueTimeline>(0);
        if (timeline != nullptr) {
            mTimelines[(uint64_t)virtualCamera.get()] = std::move(timeline);
        } else {
            LOG(WARNING) << "Failed to create a timeline. "
                         << "Client " << std::hex << virtualCamera.get()
                         << " will use v1.0 frame delivery mechanism.";
        }
    }

    // Add this virtualCamera to our ownership list via weak pointer
    mClients.emplace_back(virtualCamera);
    return true;
}


void HalCamera::disownVirtualCamera(sp<VirtualCamera> virtualCamera) {
    // Ignore calls with null pointers
    if (virtualCamera.get() == nullptr) {
        LOG(WARNING) << "Ignoring disownVirtualCamera call with null pointer";
        return;
    }

    // Remove the virtual camera from our client list
    unsigned clientCount = mClients.size();
    mClients.remove(virtualCamera);
    if (clientCount != mClients.size() + 1) {
        LOG(ERROR) << "Couldn't find camera in our client list to remove it";
    }

    // Recompute the number of buffers required with the target camera removed from the list
    if (!changeFramesInFlight(0)) {
        LOG(ERROR) << "Error when trying to reduce the in flight buffer count";
    }
}


bool HalCamera::changeFramesInFlight(int delta) {
    // Walk all our clients and count their currently required frames
    unsigned bufferCount = 0;
    for (auto&& client :  mClients) {
        sp<VirtualCamera> virtCam = client.promote();
        if (virtCam != nullptr) {
            bufferCount += virtCam->getAllowedBuffers();
        }
    }

    // Add the requested delta
    bufferCount += delta;

    // Never drop below 1 buffer -- even if all client cameras get closed
    if (bufferCount < 1) {
        bufferCount = 1;
    }

    // Ask the hardware for the resulting buffer count
    Return<EvsResult> result = mHwCamera->setMaxFramesInFlight(bufferCount);
    bool success = (result.isOk() && result == EvsResult::OK);

    // Update the size of our array of outstanding frame records
    if (success) {
        std::vector<FrameRecord> newRecords;
        newRecords.reserve(bufferCount);

        // Copy and compact the old records that are still active
        for (const auto& rec : mFrames) {
            if (rec.refCount > 0) {
                newRecords.emplace_back(rec);
            }
        }
        if (newRecords.size() > (unsigned)bufferCount) {
            LOG(WARNING) << "We found more frames in use than requested.";
        }

        mFrames.swap(newRecords);
    }

    return success;
}


bool HalCamera::changeFramesInFlight(const hidl_vec<BufferDesc_1_1>& buffers,
                                     int* delta) {
    // Return immediately if a list is empty.
    if (buffers.size() < 1) {
        LOG(DEBUG) << "No external buffers to add.";
        return true;
    }

    // Walk all our clients and count their currently required frames
    auto bufferCount = 0;
    for (auto&& client :  mClients) {
        sp<VirtualCamera> virtCam = client.promote();
        if (virtCam != nullptr) {
            bufferCount += virtCam->getAllowedBuffers();
        }
    }

    EvsResult status = EvsResult::OK;
    // Ask the hardware for the resulting buffer count
    mHwCamera->importExternalBuffers(buffers,
                                     [&](auto result, auto added) {
                                         status = result;
                                         *delta = added;
                                     });
    if (status != EvsResult::OK) {
        LOG(ERROR) << "Failed to add external capture buffers.";
        return false;
    }

    bufferCount += *delta;

    // Update the size of our array of outstanding frame records
    std::vector<FrameRecord> newRecords;
    newRecords.reserve(bufferCount);

    // Copy and compact the old records that are still active
    for (const auto& rec : mFrames) {
        if (rec.refCount > 0) {
            newRecords.emplace_back(rec);
        }
    }

    if (newRecords.size() > (unsigned)bufferCount) {
        LOG(WARNING) << "We found more frames in use than requested.";
    }

    mFrames.swap(newRecords);

    return true;
}


UniqueFence HalCamera::requestNewFrame(sp<VirtualCamera> client,
                                       const int64_t lastTimestamp) {
    if (!mSyncSupported) {
        LOG(ERROR) << "This HalCamera does not support a fence-based "
                   << "frame delivery.";
        return {};
    }

    FrameRequest req;
    req.client = client;
    req.timestamp = lastTimestamp;

    const uint64_t id = (uint64_t)client.get();

    std::lock_guard<std::mutex> lock(mFrameMutex);

    mTimelines[id]->BumpFenceEventCounter();
    UniqueFence fence = mTimelines[id]->CreateFence("FrameFence");

    mNextRequests->push_back(req);

    return fence.Dup();
}


Return<EvsResult> HalCamera::clientStreamStarting() {
    Return<EvsResult> result = EvsResult::OK;

    if (mStreamState == STOPPED) {
        mStreamState = RUNNING;
        result = mHwCamera->startVideoStream(this);
    }

    return result;
}


void HalCamera::clientStreamEnding(sp<VirtualCamera> client) {
    {
        std::lock_guard<std::mutex> lock(mFrameMutex);
        auto itReq = mNextRequests->begin();
        while (itReq != mNextRequests->end()) {
            if (itReq->client == client) {
                break;
            } else {
                ++itReq;
            }
        }

        const uint64_t clientId = reinterpret_cast<const uint64_t>(client.get());
        if (itReq != mNextRequests->end()) {
            mNextRequests->erase(itReq);

            // Signal a pending fence and delete associated timeline.
            if (mTimelines.find(clientId) != mTimelines.end()) {
                mTimelines[clientId]->BumpTimelineEventCounter();
                mTimelines.erase(clientId);
            }
        }

        auto itCam = mClients.begin();
        while (itCam != mClients.end()) {
            if (itCam->promote() == client.get()) {
                break;
            } else {
                ++itCam;
            }
        }

        if (itCam != mClients.end()) {
            // Remove a client, which requested to stop, from the list.
            mClients.erase(itCam);
        }
    }

    // Do we still have a running client?
    bool stillRunning = false;
    for (auto&& client : mClients) {
        sp<VirtualCamera> virtCam = client.promote();
        if (virtCam != nullptr) {
            stillRunning |= virtCam->isStreaming();
        }
    }

    // If not, then stop the hardware stream
    if (!stillRunning) {
        mStreamState = STOPPING;
        mHwCamera->stopVideoStream();
    }
}


Return<void> HalCamera::doneWithFrame(const BufferDesc_1_0& buffer) {
    // Find this frame in our list of outstanding frames
    unsigned i;
    for (i = 0; i < mFrames.size(); i++) {
        if (mFrames[i].frameId == buffer.bufferId) {
            break;
        }
    }
    if (i == mFrames.size()) {
        LOG(ERROR) << "We got a frame back with an ID we don't recognize!";
    } else {
        // Are there still clients using this buffer?
        mFrames[i].refCount--;
        if (mFrames[i].refCount <= 0) {
            // Since all our clients are done with this buffer, return it to the device layer
            mHwCamera->doneWithFrame(buffer);
        }
    }

    return Void();
}


Return<void> HalCamera::doneWithFrame(const BufferDesc_1_1& buffer) {
    // Find this frame in our list of outstanding frames
    unsigned i;
    for (i = 0; i < mFrames.size(); i++) {
        if (mFrames[i].frameId == buffer.bufferId) {
            break;
        }
    }
    if (i == mFrames.size()) {
        LOG(ERROR) << "We got a frame back with an ID we don't recognize!";
    } else {
        // Are there still clients using this buffer?
        mFrames[i].refCount--;
        if (mFrames[i].refCount <= 0) {
            // Since all our clients are done with this buffer, return it to the device layer
            hardware::hidl_vec<BufferDesc_1_1> returnedBuffers;
            returnedBuffers.resize(1);
            returnedBuffers[0] = buffer;
            mHwCamera->doneWithFrame_1_1(returnedBuffers);
        }
    }

    return Void();
}


// Methods from ::android::hardware::automotive::evs::V1_0::IEvsCameraStream follow.
Return<void> HalCamera::deliverFrame(const BufferDesc_1_0& buffer) {
    /* Frames are delivered via deliverFrame_1_1 callback for clients that implement
     * IEvsCameraStream v1.1 interfaces and therefore this method must not be
     * used.
     */
    LOG(INFO) << "A delivered frame from EVS v1.0 HW module is rejected.";
    mHwCamera->doneWithFrame(buffer);

    return Void();
}


// Methods from ::android::hardware::automotive::evs::V1_1::IEvsCameraStream follow.
Return<void> HalCamera::deliverFrame_1_1(const hardware::hidl_vec<BufferDesc_1_1>& buffer) {
    LOG(VERBOSE) << "Received a frame";
    // Frames are being forwarded to v1.1 clients only who requested new frame.
    const auto timestamp = buffer[0].timestamp;
    // TODO(b/145750636): For now, we are using a approximately half of 1 seconds / 30 frames = 33ms
    //           but this must be derived from current framerate.
    constexpr int64_t kThreshold = 16 * 1e+3; // ms
    unsigned frameDeliveriesV1 = 0;
    if (mSyncSupported) {
        std::lock_guard<std::mutex> lock(mFrameMutex);
        std::swap(mCurrentRequests, mNextRequests);
        while (!mCurrentRequests->empty()) {
            auto req = mCurrentRequests->front(); mCurrentRequests->pop_front();
            sp<VirtualCamera> vCam = req.client.promote();
            if (vCam == nullptr) {
                // Ignore a client already dead.
                continue;
            } else if (timestamp - req.timestamp < kThreshold) {
                // Skip current frame because it arrives too soon.
                LOG(DEBUG) << "Skips a frame from " << getId();
                mNextRequests->push_back(req);
                ++mSyncFrames;
            } else if (vCam != nullptr && vCam->deliverFrame(buffer[0])) {
                // Forward a frame and move a timeline.
                LOG(DEBUG) << getId() << " forwarded the buffer #" << buffer[0].bufferId;
                mTimelines[(uint64_t)vCam.get()]->BumpTimelineEventCounter();
                ++frameDeliveriesV1;
            }
        }
    }
    ++mFramesReceived;

    // Frames are being forwarded to active v1.0 clients and v1.1 clients if we
    // failed to create a timeline.
    unsigned frameDeliveries = 0;
    for (auto&& client : mClients) {
        sp<VirtualCamera> vCam = client.promote();
        if (vCam == nullptr || (mSyncSupported && vCam->getVersion() > 0)) {
            continue;
        }

        if (vCam->deliverFrame(buffer[0])) {
            ++frameDeliveries;
        }
    }

    frameDeliveries += frameDeliveriesV1;
    if (frameDeliveries < 1) {
        // If none of our clients could accept the frame, then return it
        // right away.
        LOG(INFO) << "Trivially rejecting frame (" << buffer[0].bufferId
                  << ") from " << getId() << " with no acceptance";
        ++mFramesNotUsed;
        mHwCamera->doneWithFrame_1_1(buffer);
    } else {
        // Add an entry for this frame in our tracking list.
        unsigned i;
        for (i = 0; i < mFrames.size(); ++i) {
            if (mFrames[i].refCount == 0) {
                break;
            }
        }

        if (i == mFrames.size()) {
            mFrames.emplace_back(buffer[0].bufferId);
        } else {
            mFrames[i].frameId = buffer[0].bufferId;
        }
        mFrames[i].refCount = frameDeliveries;
    }

    return Void();
}


Return<void> HalCamera::notify(const EvsEventDesc& event) {
    LOG(DEBUG) << "Received an event id: " << static_cast<int32_t>(event.aType);
    if(event.aType == EvsEventType::STREAM_STOPPED) {
        // This event happens only when there is no more active client.
        if (mStreamState != STOPPING) {
            LOG(WARNING) << "Stream stopped unexpectedly";
        }

        mStreamState = STOPPED;
    }

    // Forward all other events to the clients
    for (auto&& client : mClients) {
        sp<VirtualCamera> vCam = client.promote();
        if (vCam != nullptr) {
            if (!vCam->notify(event)) {
                LOG(INFO) << "Failed to forward an event";
            }
        }
    }

    return Void();
}


Return<EvsResult> HalCamera::setMaster(sp<VirtualCamera> virtualCamera) {
    if (mMaster == nullptr) {
        LOG(DEBUG) << __FUNCTION__
                   << ": " << virtualCamera.get() << " becomes a master.";
        mMaster = virtualCamera;
        return EvsResult::OK;
    } else {
        LOG(INFO) << "This camera already has a master client.";
        return EvsResult::OWNERSHIP_LOST;
    }
}


Return<EvsResult> HalCamera::forceMaster(sp<VirtualCamera> virtualCamera) {
    sp<VirtualCamera> prevMaster = mMaster.promote();
    if (prevMaster == virtualCamera) {
        LOG(DEBUG) << "Client " << virtualCamera.get()
                   << " is already a master client";
    } else {
        mMaster = virtualCamera;
        if (prevMaster != nullptr) {
            LOG(INFO) << "High priority client " << virtualCamera.get()
                      << " steals a master role from " << prevMaster.get();

            /* Notify a previous master client the loss of a master role */
            EvsEventDesc event;
            event.aType = EvsEventType::MASTER_RELEASED;
            if (!prevMaster->notify(event)) {
                LOG(ERROR) << "Fail to deliver a master role lost notification";
            }
        }
    }

    return EvsResult::OK;
}


Return<EvsResult> HalCamera::unsetMaster(sp<VirtualCamera> virtualCamera) {
    if (mMaster.promote() != virtualCamera) {
        return EvsResult::INVALID_ARG;
    } else {
        LOG(INFO) << "Unset a master camera client";
        mMaster = nullptr;

        /* Notify other clients that a master role becomes available. */
        EvsEventDesc event;
        event.aType = EvsEventType::MASTER_RELEASED;
        auto cbResult = this->notify(event);
        if (!cbResult.isOk()) {
            LOG(ERROR) << "Fail to deliver a parameter change notification";
        }

        return EvsResult::OK;
    }
}


Return<EvsResult> HalCamera::setParameter(sp<VirtualCamera> virtualCamera,
                                          CameraParam id, int32_t& value) {
    EvsResult result = EvsResult::INVALID_ARG;
    if (virtualCamera == mMaster.promote()) {
        mHwCamera->setIntParameter(id, value,
                                   [&result, &value](auto status, auto readValue) {
                                       result = status;
                                       value = readValue[0];
                                   });

        if (result == EvsResult::OK) {
            /* Notify a parameter change */
            EvsEventDesc event;
            event.aType = EvsEventType::PARAMETER_CHANGED;
            event.payload[0] = static_cast<uint32_t>(id);
            event.payload[1] = static_cast<uint32_t>(value);
            auto cbResult = this->notify(event);
            if (!cbResult.isOk()) {
                LOG(ERROR) << "Fail to deliver a parameter change notification";
            }
        }
    } else {
        LOG(WARNING) << "A parameter change request from a non-master client is declined.";

        /* Read a current value of a requested camera parameter */
        getParameter(id, value);
    }

    return result;
}


Return<EvsResult> HalCamera::getParameter(CameraParam id, int32_t& value) {
    EvsResult result = EvsResult::OK;
    mHwCamera->getIntParameter(id, [&result, &value](auto status, auto readValue) {
                                       result = status;
                                       if (result == EvsResult::OK) {
                                           value = readValue[0];
                                       }
    });

    return result;
}


void HalCamera::dump(int fd) const {
    dprintf(fd, "HalCamera: %s\n", mId.c_str());
    const auto timeElapsedNano = android::elapsedRealtimeNano() - mTimeCreated;
    dprintf(fd, "\tCreated: %ld (elapsed %ld ns)\n", (long)mTimeCreated, (long)timeElapsedNano);
    dprintf(fd, "\tFrames received: %lu (%f fps)\n",
                (unsigned long)mFramesReceived,
                (double)mFramesReceived / timeElapsedNano * 1e+9);
    dprintf(fd, "\tFrames not used: %lu\n", (unsigned long)mFramesNotUsed);
    dprintf(fd, "\tFrames skipped to sync: %lu\n", (unsigned long)mSyncFrames);
    dprintf(fd, "\tActive Stream Configuration:\n");
    dprintf(fd, "\t\tid: %d\n", mStreamConfig.id);
    dprintf(fd, "\t\twidth: %d\n", mStreamConfig.width);
    dprintf(fd, "\t\theight: %d\n", mStreamConfig.height);
    dprintf(fd, "\t\tformat: %d\n", mStreamConfig.width);
    dprintf(fd, "\t\tusage: 0x%lX\n", (unsigned long)mStreamConfig.usage);
    dprintf(fd, "\t\trotation: 0x%X\n", mStreamConfig.rotation);

    dprintf(fd, "\tActive clients:\n");
    for (auto&& client : mClients) {
        auto handle = client.promote();
        if (!handle) {
            continue;
        }

        dprintf(fd, "\t\tClient %p\n", handle.get());
        handle->dump(fd, "\t\t\t");
        {
            std::scoped_lock<std::mutex> lock(mFrameMutex);
            dprintf(fd, "\t\t\tUse a fence-based delivery: %s\n",
                    mTimelines.find((uint64_t)handle.get()) != mTimelines.end() ? "T" : "F");
        }
    }

    dprintf(fd, "\tMaster client: %p\n", mMaster.promote().get());
    dprintf(fd, "\tSynchronization support: %s\n", mSyncSupported ? "T" : "F");
}


double HalCamera::getFramerate() const {
    const auto timeElapsed = android::elapsedRealtimeNano() - mTimeCreated;
    return static_cast<double>(mFramesReceived) / timeElapsed;
}


} // namespace implementation
} // namespace V1_1
} // namespace evs
} // namespace automotive
} // namespace android