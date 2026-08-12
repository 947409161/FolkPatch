mod apd;
mod assets;
mod cli;
mod defs;
mod event;
mod init;
mod insmod;
mod late_load;
mod lkm;
mod lua;
mod magic_mount;
mod magica;
mod metamodule;
mod module;
mod module_config;
mod package;
mod plugin;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod pty;
mod resetprop;
mod restorecon;
mod sepolicy;
mod supercall;
mod utils;
fn main() -> anyhow::Result<()> {
    if init::is_init_process() {
        return init::run();
    }
    cli::run()
}
