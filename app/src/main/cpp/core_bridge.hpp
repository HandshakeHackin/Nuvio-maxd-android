#pragma once
#include "util.h"
#include <memory>
namespace nuvio_android {
json call(const std::string& command, const json& arguments);
void cancel();
void shutdown();
struct TorrentReader;
std::shared_ptr<TorrentReader> open_torrent(const std::string& hash, int index, const std::string& profile_key = "");
int64_t torrent_size(const std::shared_ptr<TorrentReader>& reader);
int read_torrent(const std::shared_ptr<TorrentReader>& reader, int64_t offset, uint8_t* data, int length);
void cancel_torrent(const std::shared_ptr<TorrentReader>& reader);
}
