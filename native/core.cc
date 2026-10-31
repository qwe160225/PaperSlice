#include "core.hh"
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFJob.hh>
#include <qpdf/QPDFLogger.hh>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <stdexcept>
#include <vector>

int page_count(std::string const& path, std::string const& password, bool repair) {
    QPDF pdf;
    pdf.setAttemptRecovery(repair);
    pdf.setSuppressWarnings(true);
    pdf.processFile(path.c_str(), password.c_str());
    return static_cast<int>(QPDFPageDocumentHelper(pdf).getAllPages().size());
}
bool extract_pages(std::string const& path, std::string const& password,
    std::string const& selection, std::string const& output, int expected,
    bool repair, std::function<void(int)> progress) {
    if (path == output) throw std::runtime_error("不得覆盖源文件");
    bool warnings = false;
    {
        QPDF source;
        source.setAttemptRecovery(repair);
        source.setSuppressWarnings(true);
        source.processFile(path.c_str(), password.c_str());
        if (static_cast<int>(QPDFPageDocumentHelper(source).getAllPages().size()) != expected)
            throw std::runtime_error("源文件已发生变化，请重新选择文件");
        if (!source.allowModifyAssembly() && !source.ownerPasswordMatched())
            throw std::runtime_error("此 PDF 不允许重组页面，请使用所有者密码");
        warnings = !source.getWarnings().empty();
    }
    // Use QPDF's mature page copying and reference repair, starting with an empty document.
    // Explicit resource cleanup prevents shared dictionaries from keeping discarded images.
    std::vector<std::string> args = {"paperslice", "--empty", "--pages", path,
        "--password=" + password, selection, "--", "--remove-unreferenced-resources=yes",
        "--stream-data=preserve", "--progress", output};
    if (!repair) args.insert(args.begin() + 1, "--suppress-recovery");
    std::vector<char const*> argv;
    for (auto const& arg: args) argv.push_back(arg.c_str());
    argv.push_back(nullptr);
    QPDFJob job;
    auto logger = QPDFLogger::create();
    logger->setInfo(logger->discard());
    logger->setWarn(logger->discard());
    logger->setError(logger->discard());
    job.setLogger(logger);
    job.initializeFromArgv(argv.data());
    job.registerProgressReporter(progress);
    job.run();
    if (job.getExitCode() != 0 && job.getExitCode() != 3)
        throw std::runtime_error("QPDF 未能完成提取");
    return warnings || job.hasWarnings();
}
