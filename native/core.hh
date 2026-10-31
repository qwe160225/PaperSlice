#pragma once
#include <string>
#include <functional>
int page_count(std::string const& path, std::string const& password, bool repair);
bool extract_pages(std::string const& path, std::string const& password,
    std::string const& selection, std::string const& output, int expected,
    bool repair, std::function<void(int)> progress);
