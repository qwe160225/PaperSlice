#include "core.hh"
#include <iostream>
int main(int argc, char** argv) {
    try {
        if (argc < 3) return 2;
        std::string pass = argc > 5 ? argv[5] : "";
        bool repair = argc > 6 && std::string(argv[6]) == "repair";
        int count = page_count(argv[2], pass, repair);
        if (std::string(argv[1]) == "count") { std::cout << count << '\n'; return 0; }
        if (argc < 5) return 2;
        bool warning = extract_pages(argv[2], pass, argv[3], argv[4], count, repair, [](int){});
        std::cout << (warning ? "warning" : "ok") << '\n';
        return 0;
    } catch (std::exception const& e) { std::cerr << e.what() << '\n'; return 2; }
}
