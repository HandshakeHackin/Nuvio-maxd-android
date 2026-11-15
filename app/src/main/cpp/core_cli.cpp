#include "core_bridge.hpp"
#include <iostream>
#include <algorithm>
#include <cstdio>
#include <unistd.h>
int main() {
    FILE* protocol=fdopen(dup(STDOUT_FILENO),"w");
    if(!protocol)return 2;
    dup2(STDERR_FILENO,STDOUT_FILENO);
    const auto reply=[&](const json& value){const auto line=value.dump();fprintf(protocol,"%s\n",line.c_str());fflush(protocol);};
    std::string line;
    while (std::getline(std::cin,line)) {
        try {
            const auto request=json::parse(line);
            if(jstr(request,"command")=="test.torrent.read") {
                const auto args=jobj(request,"arguments");
                auto reader=nuvio_android::open_torrent(jstr(args,"hash"),int(jnum(args,"index")));
                std::vector<uint8_t> data(size_t(std::clamp(jnum(args,"length",1024),1.0,1048576.0)));
                const auto count=nuvio_android::read_torrent(reader,int64_t(jnum(args,"offset")),data.data(),int(data.size()));
                std::string hex;static const char digits[]="0123456789abcdef";
                for(int i=0;i<count;i++){hex+=digits[data[i]>>4];hex+=digits[data[i]&15];}
                reply(json{{"ok",count>=0},{"result",{{"size",nuvio_android::torrent_size(reader)},{"count",count},{"hex",hex}}}});
                continue;
            }
            reply(nuvio_android::call(jstr(request,"command"),jobj(request,"arguments")));
        } catch(const std::exception&) {
            reply(json{{"ok",false},{"error","Invalid request"}});
        }
    }
    nuvio_android::shutdown();
    fclose(protocol);
}
