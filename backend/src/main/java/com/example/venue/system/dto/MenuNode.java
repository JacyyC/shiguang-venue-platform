package com.example.venue.system.dto;

import com.example.venue.system.entity.SysMenu;

import java.util.ArrayList;
import java.util.List;

public class MenuNode {
    public Long id;
    public Long parentId;
    public String name;
    public String path;
    public String component;
    public String icon;
    public String perms;
    public String type;
    public List<MenuNode> children = new ArrayList<>();

    public static MenuNode from(SysMenu menu) {
        MenuNode node = new MenuNode();
        node.id = menu.id;
        node.parentId = menu.parentId;
        node.name = menu.menuName;
        node.path = menu.path;
        node.component = menu.component;
        node.icon = menu.icon;
        node.perms = menu.perms;
        node.type = menu.menuType;
        return node;
    }
}
