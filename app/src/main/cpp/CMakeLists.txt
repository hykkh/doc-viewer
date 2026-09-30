cmake_minimum_required(VERSION 3.22.1)
project(dvlok C)
add_library(dvlok SHARED dvlok.c)
# 16KB-page devices
target_link_options(dvlok PRIVATE "-Wl,-z,max-page-size=16384")
