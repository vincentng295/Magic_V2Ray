// Host test harness: runs the proxy_control applet as its own executable.
int run_proxy_control(int argc, char* argv[]);
int main(int argc, char* argv[]) { return run_proxy_control(argc, argv); }
