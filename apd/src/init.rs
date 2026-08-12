//! Early init loader used by the KernelSU-style LKM boot image flow.
//!
//! The binary is copied into the ramdisk as `/init`. It loads the bundled
//! KernelPatch module before handing control to the original Android init.

use anyhow::{Context, Result};
use std::ffi::{CStr, CString};
use std::os::unix::process::CommandExt;
use std::process::Command;

use crate::insmod;

const MODULE_PATH: &str = "/kernelpatch.ko";

pub fn is_init_process() -> bool {
    std::env::args_os()
        .next()
        .and_then(|arg| arg.into_string().ok())
        .is_some_and(|arg| {
            let name = std::path::Path::new(&arg)
                .file_name()
                .and_then(|name| name.to_str());
            name == Some("init")
        })
}

fn mount_fs(source: &CStr, target: &CStr, filesystem: &CStr) -> Result<bool> {
    let result = unsafe {
        libc::mount(
            source.as_ptr(),
            target.as_ptr(),
            filesystem.as_ptr(),
            0,
            std::ptr::null(),
        )
    };
    if result != 0 {
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::EBUSY) {
            return Err(error).context("mount early init filesystem failed");
        }
        return Ok(false);
    }
    Ok(true)
}

struct EarlyMounts(Vec<CString>);

impl Drop for EarlyMounts {
    fn drop(&mut self) {
        for mountpoint in self.0.iter().rev() {
            unsafe {
                libc::umount2(mountpoint.as_ptr(), libc::MNT_DETACH);
            }
        }
    }
}

fn prepare_kernel_interfaces() -> Result<EarlyMounts> {
    std::fs::create_dir_all("/proc").ok();
    std::fs::create_dir_all("/sys").ok();

    let proc = CString::new("proc")?;
    let sysfs = CString::new("sysfs")?;
    let proc_path = CString::new("/proc")?;
    let sys_path = CString::new("/sys")?;
    let mut mounts = Vec::new();
    if mount_fs(&proc, &proc_path, &proc)? {
        mounts.push(proc_path);
    }
    if mount_fs(&sysfs, &sys_path, &sysfs)? {
        mounts.push(sys_path);
    }
    Ok(EarlyMounts(mounts))
}

fn load_kernelpatch() -> Result<()> {
    if std::path::Path::new("/sys/module/kernelpatch").exists() {
        return Ok(());
    }

    let module = std::fs::read(MODULE_PATH)
        .with_context(|| format!("read early kernel module {MODULE_PATH} failed"))?;
    let params = CString::new("")?;
    insmod::load_module(&module, &params).context("load early kernelpatch module failed")
}

fn prepare_init_handoff() -> Result<()> {
    // A failed LKM load must not prevent Android from booting. This mirrors
    // ksuinit: report the error, then always transfer control to real init.
    if let Err(error) = prepare_kernel_interfaces().and_then(|_mounts| load_kernelpatch()) {
        eprintln!("apinit: {error:#}");
    }

    let real_init = if std::path::Path::new("/init.real").exists() {
        "init.real"
    } else {
        "/system/bin/init"
    };
    std::fs::remove_file("/init").context("remove temporary /init failed")?;
    std::os::unix::fs::symlink(real_init, "/init").context("link original init failed")?;
    Ok(())
}

pub fn run() -> Result<()> {
    prepare_init_handoff()?;

    // Keep argv[0] as /init. Android init uses it to select first-stage init
    // behavior, which is why ksuinit execs the symlink instead of init.real.
    let mut args = std::env::args_os();
    args.next();
    let mut command = Command::new("/init");
    command.args(args);
    let error = command.exec();
    Err(error).context("exec /init failed")
}

/// Transfer control without reconstructing argv/envp through Rust std. This
/// is used by the no-main early loader, matching ksuinit's C entry point.
pub unsafe fn run_raw(
    argv: *const *const libc::c_char,
    envp: *const *const libc::c_char,
) -> Result<()> {
    prepare_init_handoff()?;
    let init = CString::new("/init")?;
    let result = unsafe { libc::execve(init.as_ptr(), argv, envp) };
    if result == -1 {
        Err(std::io::Error::last_os_error()).context("exec /init failed")
    } else {
        Ok(())
    }
}

/// Try the platform init directly after the loader itself cannot complete the
/// handoff. The caller must terminate if this call returns.
pub unsafe fn exec_fallback(
    argv: *const *const libc::c_char,
    envp: *const *const libc::c_char,
) -> ! {
    for path in ["/init.real", "/system/bin/init"] {
        if let Ok(init) = CString::new(path) {
            unsafe {
                libc::execve(init.as_ptr(), argv, envp);
            }
        }
    }
    unsafe { libc::_exit(127) }
}
